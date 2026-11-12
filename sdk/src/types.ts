/** The error shape every Tsukuyomi endpoint uses, plus the HTTP status. */
export interface ApiError {
  status: number
  code: string
  message: string
}

/**
 * Every call resolves to one of these and never throws for a server-side
 * refusal. You check `error` first:
 *
 *   const { data, error } = await db.from('tasks').select()
 *   if (error) { ... } else { use(data) }
 */
export type Result<T> =
  | { data: T; error: null; count: number | null }
  | { data: null; error: ApiError; count: null }

export interface User {
  id: string
  email: string
}

export interface Session {
  access_token: string
  refresh_token: string
  /** Milliseconds since 1970 at which the access token stops working. */
  expires_at: number
  user: User
}

/** Somewhere to keep the session between page loads. localStorage fits. */
export interface SessionStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface ClientOptions {
  /** Where the login is remembered. Default: localStorage if it exists. Pass null to keep it in memory only. */
  storage?: SessionStorage | null
  /** Replaceable for tests. */
  fetch?: typeof fetch
  /** Replaceable for tests and for runtimes without a global WebSocket. */
  WebSocket?: new (url: string) => WebSocket
}

export function ok<T>(data: T, count: number | null = null): Result<T> {
  return { data, error: null, count }
}

export function fail<T>(status: number, code: string, message: string): Result<T> {
  return { data: null, error: { status, code, message }, count: null }
}
