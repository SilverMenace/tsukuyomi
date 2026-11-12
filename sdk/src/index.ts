import { Auth } from './auth.ts'
import { Live } from './live.ts'
import type { LiveHandlers } from './live.ts'
import { Query } from './query.ts'
import type { Transport } from './query.ts'
import type { ClientOptions } from './types.ts'

export type { ApiError, ClientOptions, Result, Session, SessionStorage, User } from './types.ts'
export type { LiveEvent, LiveHandlers } from './live.ts'
export { Auth, Live, Query }

/**
 * Everything an app needs to use one Tsukuyomi project from the browser.
 *
 *   const db = createClient('https://your-host', 'tsk_anon_...')
 *   await db.auth.signIn(email, password)
 *   const { data, error } = await db.from('tasks').select().eq('done', false)
 *   const stop = db.subscribe('tasks', { onEvent: (e) => ..., onResync: () => reload() })
 *
 * Only ever give it the ANON key. What that key may do is decided by the
 * rules set in the dashboard, and enforced by the database, so it is safe to
 * ship in a web page. The service key bypasses the rules and must stay on a server.
 */
export class TsukuyomiClient {
  readonly auth: Auth
  private readonly transport: Transport
  private readonly live: Live

  constructor(url: string, anonKey: string, options: ClientOptions = {}) {
    const base = url.replace(/\/+$/, '')
    this.auth = new Auth(base, anonKey, options)
    this.transport = {
      url: base,
      anonKey,
      auth: this.auth,
      fetch: options.fetch ?? ((input, init) => fetch(input, init)),
    }
    this.live = new Live(base, anonKey, this.auth, options.WebSocket)
  }

  /** Starts a query on one table. Give the row type for typed results: db.from<Task>('tasks') */
  from<Row = Record<string, unknown>>(table: string): Query<Row> {
    return new Query<Row>(this.transport, table)
  }

  /** Live changes of one table, as far as the rules let you see them. Returns a function that stops it. */
  subscribe<Row = Record<string, unknown>>(table: string, handlers: LiveHandlers<Row>): () => void {
    return this.live.subscribe(table, handlers)
  }
}

export function createClient(url: string, anonKey: string, options?: ClientOptions): TsukuyomiClient {
  return new TsukuyomiClient(url, anonKey, options)
}
