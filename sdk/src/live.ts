import type { Auth } from './auth.ts'

/** One change to a row you are allowed to see. */
export interface LiveEvent<Row = Record<string, unknown>> {
  table: string
  op: 'insert' | 'update' | 'delete'
  /** The row's id. */
  id: string
  /** The row as it is now. null for a delete. */
  row: Row | null
}

export interface LiveHandlers<Row = Record<string, unknown>> {
  onEvent: (event: LiveEvent<Row>) => void
  /**
   * The server cannot tell you what you missed (you were away too long, you
   * logged in or out, or the server lost its own feed). Load the table again.
   */
  onResync?: () => void
  onError?: (code: string, message: string) => void
}

type Handlers = LiveHandlers<never>

interface Subscription {
  handlers: Set<Handlers>
  /** The last cursor the server gave us for this table; sent back as "since" after a reconnect. */
  cursor: string | null
  /** False until the server has confirmed the subscription once. */
  confirmed: boolean
}

/** How many event ids to remember for spotting repeats. */
const REMEMBER_EVENTS = 500
const FIRST_RETRY_MS = 1000
const MAX_RETRY_MS = 30_000

/**
 * One WebSocket to /realtime/v1, shared by every subscription.
 *
 * It opens when the first subscription is made and closes with the last. If
 * the connection drops it reconnects (waiting 1 s, 2 s, 4 s ... up to 30 s)
 * and asks the server for what it missed, using the cursor the server sends
 * with every message. Replay can repeat an event, so event ids already seen
 * are skipped. (Server side of this: DECISIONS.md D33, D34.)
 */
export class Live {
  private socket: WebSocket | null = null
  private readonly subscriptions = new Map<string, Subscription>()
  private readonly seen = new Set<string>()
  private retryMs = FIRST_RETRY_MS
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  /** Set when the server rejected our token, so the next connection gets a brand-new one. */
  private needFreshToken = false
  private readonly url: string
  private readonly anonKey: string
  private readonly auth: Auth
  private readonly Socket: new (url: string) => WebSocket

  constructor(url: string, anonKey: string, auth: Auth, Socket?: new (url: string) => WebSocket) {
    this.url = url.replace(/^http/, 'ws') + '/realtime/v1'
    this.anonKey = anonKey
    this.auth = auth
    this.Socket = Socket ?? WebSocket
    // A connection has one identity for its whole life. After a login or
    // logout, start over as the new identity; what is visible has changed, so
    // old cursors are meaningless and every subscriber must reload.
    auth.onChange(() => {
      this.subscriptions.forEach((subscription) => (subscription.cursor = null))
      this.reconnectNow()
    })
  }

  /** Starts delivering changes of one table. Returns a function that stops it. */
  subscribe<Row>(table: string, handlers: LiveHandlers<Row>): () => void {
    let subscription = this.subscriptions.get(table)
    if (!subscription) {
      subscription = { handlers: new Set(), cursor: null, confirmed: false }
      this.subscriptions.set(table, subscription)
      if (this.ready()) {
        this.send({ type: 'subscribe', table })
      }
    }
    subscription.handlers.add(handlers as Handlers)
    this.connect()

    return () => {
      const current = this.subscriptions.get(table)
      if (!current?.handlers.delete(handlers as Handlers) || current.handlers.size > 0) {
        return
      }
      this.subscriptions.delete(table)
      if (this.ready()) {
        this.send({ type: 'unsubscribe', table })
      }
      if (this.subscriptions.size === 0) {
        this.disconnect()
      }
    }
  }

  private authenticated = false

  private ready(): boolean {
    return this.authenticated && this.socket?.readyState === 1
  }

  private connect(): void {
    if (this.socket || this.retryTimer || this.subscriptions.size === 0) {
      return
    }
    const socket = new this.Socket(this.url)
    this.socket = socket
    this.authenticated = false
    socket.onopen = async () => {
      const token = await this.auth.accessToken(this.needFreshToken)
      this.needFreshToken = false
      if (this.socket === socket) {
        // Browsers cannot set headers on a WebSocket, so credentials go in the first message.
        this.send({ type: 'auth', apikey: this.anonKey, ...(token ? { token } : {}) })
      }
    }
    socket.onmessage = (message) => {
      if (this.socket === socket) {
        this.receive(JSON.parse(String(message.data)))
      }
    }
    socket.onclose = () => {
      if (this.socket === socket) {
        this.socket = null
        this.authenticated = false
        this.retryLater()
      }
    }
    socket.onerror = () => undefined // a close event always follows
  }

  private receive(message: Record<string, any>): void {
    const subscription = message.table ? this.subscriptions.get(message.table) : undefined
    switch (message.type) {
      case 'ready':
        this.authenticated = true
        this.retryMs = FIRST_RETRY_MS
        this.subscriptions.forEach((s, table) =>
          this.send({ type: 'subscribe', table, ...(s.cursor ? { since: s.cursor } : {}) }))
        break
      case 'subscribed':
        if (subscription) {
          // "resync" from the server, or a re-subscription that had no cursor to
          // offer: either way, changes may have been missed.
          const missedSomething = message.resync === true || (subscription.confirmed && !subscription.cursor)
          subscription.cursor = message.cursor
          subscription.confirmed = true
          if (missedSomething) {
            subscription.handlers.forEach((h) => h.onResync?.())
          }
        }
        break
      case 'event':
        if (subscription && this.isNew(message.eventId)) {
          subscription.cursor = message.cursor
          const event = { table: message.table, op: message.op, id: message.id, row: message.row }
          subscription.handlers.forEach((h) => h.onEvent(event as never))
        }
        break
      case 'resync':
        if (subscription) {
          subscription.cursor = message.cursor
          subscription.handlers.forEach((h) => h.onResync?.())
        }
        break
      case 'error':
        this.failed(message.code, message.message)
        break
    }
  }

  /**
   * After these errors the server stops talking to this connection. We close
   * our side at once instead of waiting for the server's close (which can
   * arrive late behind a proxy), then reconnect if that can help.
   */
  private failed(code: string, text: string): void {
    if (code === 'invalid_token' || code === 'token_expired') {
      this.needFreshToken = true
      this.reconnectNow()
    } else if (code === 'access_changed') {
      this.reconnectNow()
    } else {
      this.subscriptions.forEach((s) => s.handlers.forEach((h) => h.onError?.(code, text)))
      if (code === 'invalid_key') {
        this.disconnect() // retrying with the same key cannot work
      }
    }
  }

  private isNew(eventId: string): boolean {
    if (this.seen.has(eventId)) {
      return false
    }
    this.seen.add(eventId)
    if (this.seen.size > REMEMBER_EVENTS) {
      // A Set keeps insertion order, so the first entry is the oldest.
      this.seen.delete(this.seen.values().next().value!)
    }
    return true
  }

  private send(message: object): void {
    this.socket?.send(JSON.stringify(message))
  }

  private reconnectNow(): void {
    this.disconnect()
    this.connect()
  }

  private retryLater(): void {
    if (this.subscriptions.size === 0 || this.retryTimer) {
      return
    }
    // Random spread so many clients do not all come back in the same instant.
    const wait = this.retryMs * (0.5 + Math.random() / 2)
    this.retryMs = Math.min(this.retryMs * 2, MAX_RETRY_MS)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, wait)
  }

  private disconnect(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    const socket = this.socket
    this.socket = null // first, so the close handler does not schedule a retry
    this.authenticated = false
    socket?.close()
  }
}
