import type { ClientOptions, Result, Session, SessionStorage, User } from './types.ts'
import { fail, ok } from './types.ts'

/** Refresh this long before the access token expires, so a request never leaves with a token about to die. */
const REFRESH_EARLY_MS = 60_000

type Listener = (user: User | null) => void

/**
 * End users of a project: sign up, log in, stay logged in.
 *
 * The server hands out two tokens. The access token proves who you are and
 * lasts an hour. The refresh token lasts 30 days, works exactly once, and is
 * traded for a new pair. This class hides that trade: ask it for
 * accessToken() and it returns one that is still good.
 */
export class Auth {
  private session: Session | null = null
  private refreshing: Promise<Session | null> | null = null
  private readonly listeners = new Set<Listener>()
  private readonly storage: SessionStorage | null
  private readonly storageKey: string
  private readonly doFetch: typeof fetch

  private readonly url: string
  private readonly anonKey: string

  constructor(url: string, anonKey: string, options: ClientOptions) {
    this.url = url
    this.anonKey = anonKey
    this.doFetch = options.fetch ?? ((input, init) => fetch(input, init))
    this.storage = options.storage !== undefined ? options.storage : defaultStorage()
    // One login per project per site: the key prefix identifies the project.
    this.storageKey = `tsukuyomi.session.${anonKey.slice(0, 18)}`
    this.session = this.read()
  }

  /** The logged-in user, or null. */
  get user(): User | null {
    return this.session?.user ?? null
  }

  signUp(email: string, password: string): Promise<Result<User>> {
    return this.enter('signup', email, password)
  }

  signIn(email: string, password: string): Promise<Result<User>> {
    return this.enter('login', email, password)
  }

  async signOut(): Promise<void> {
    const session = this.session
    this.store(null)
    if (session) {
      // Best effort: tell the server to forget the refresh token.
      await this.post('logout', { refresh_token: session.refresh_token }).catch(() => undefined)
    }
  }

  /** Calls back now-and-later whenever someone logs in or out. Returns a function that stops it. */
  onChange(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /**
   * An access token that is valid right now, or null if nobody is logged in.
   * @param force get a new one even if the current one has not expired (the server just rejected it)
   */
  async accessToken(force = false): Promise<string | null> {
    if (!this.session) {
      return null
    }
    if (!force && Date.now() < this.session.expires_at - REFRESH_EARLY_MS) {
      return this.session.access_token
    }
    // Many requests can ask at once; they all wait for the same single refresh.
    // (A refresh token works once, so two parallel refreshes would log the user out.)
    this.refreshing ??= this.refresh(this.session).finally(() => (this.refreshing = null))
    return (await this.refreshing)?.access_token ?? null
  }

  private async refresh(old: Session): Promise<Session | null> {
    let response: Response
    try {
      response = await this.post('refresh', { refresh_token: old.refresh_token })
    } catch {
      return old // offline: keep the session, the request itself will report the problem
    }
    if (response.ok) {
      return this.store(toSession(await response.json()))
    }
    // Another tab may have refreshed first and saved a newer session.
    const saved = this.read()
    if (saved && saved.refresh_token !== old.refresh_token) {
      this.session = saved
      return saved
    }
    return this.store(null) // the login is over
  }

  private async enter(path: 'signup' | 'login', email: string, password: string): Promise<Result<User>> {
    let response: Response
    try {
      response = await this.post(path, { email, password })
    } catch (e) {
      return fail(0, 'network', String(e))
    }
    const body = await response.json().catch(() => null)
    if (!response.ok) {
      return fail(response.status, body?.error?.code ?? 'error', body?.error?.message ?? response.statusText)
    }
    const session = this.store(toSession(body))!
    return ok(session.user)
  }

  private post(path: string, body: unknown): Promise<Response> {
    return this.doFetch(`${this.url}/auth/v1/${path}`, {
      method: 'POST',
      headers: { apikey: this.anonKey, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    })
  }

  private store(session: Session | null): Session | null {
    const before = this.session?.user.id ?? null
    this.session = session
    try {
      if (session) {
        this.storage?.setItem(this.storageKey, JSON.stringify(session))
      } else {
        this.storage?.removeItem(this.storageKey)
      }
    } catch {
      // storage full or blocked: the session still works until the page closes
    }
    if (before !== (session?.user.id ?? null)) {
      this.listeners.forEach((listener) => listener(session?.user ?? null))
    }
    return session
  }

  private read(): Session | null {
    try {
      const text = this.storage?.getItem(this.storageKey)
      const session = text ? (JSON.parse(text) as Session) : null
      return session?.access_token && session.refresh_token && session.user?.id ? session : null
    } catch {
      return null
    }
  }
}

function toSession(body: { access_token: string; refresh_token: string; expires_in: number; user: User }): Session {
  return {
    access_token: body.access_token,
    refresh_token: body.refresh_token,
    expires_at: Date.now() + body.expires_in * 1000,
    user: body.user,
  }
}

function defaultStorage(): SessionStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // some browsers throw when storage is blocked
  }
}
