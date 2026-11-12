import type { Auth } from './auth.ts'
import type { Result } from './types.ts'
import { fail, ok } from './types.ts'

type Value = string | number | boolean
type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE'

/** What a builder needs from the client to actually send a request. */
export interface Transport {
  url: string
  anonKey: string
  auth: Auth
  fetch: typeof fetch
}

/**
 * Builds one request to /api/v1/<table> step by step and sends it when awaited.
 *
 *   await db.from('tasks').select('title,lists(name)').eq('done', false).order('created_at', { ascending: false }).limit(10)
 *   await db.from('tasks').insert({ title: 'milk' })
 *   await db.from('tasks').update({ done: true }).eq('id', id)
 *   await db.from('tasks').delete().eq('id', id)
 *
 * It only assembles the URL the REST API documents (README, "REST API"); all
 * checking is done by the server. Nothing here needs to be trusted.
 */
export class Query<Row = Record<string, unknown>, Out = Row[]> implements PromiseLike<Result<Out>> {
  private method: Method = 'GET'
  private body: unknown = undefined
  private wantCount = false
  private readonly params: [string, string][] = []

  private readonly transport: Transport
  private readonly table: string

  constructor(transport: Transport, table: string) {
    this.transport = transport
    this.table = table
  }

  /** @param columns e.g. 'title,done' or with related rows 'title,lists(name)'. Default: all columns. */
  select(columns?: string, options: { count?: boolean } = {}): Query<Row, Row[]> {
    this.method = 'GET'
    if (columns && columns !== '*') {
      this.params.push(['select', columns])
    }
    this.wantCount = options.count === true
    return this as unknown as Query<Row, Row[]>
  }

  /** One object inserts one row and returns it; an array inserts many and returns them. */
  insert(row: Partial<Row>): Query<Row, Row>
  insert(rows: Partial<Row>[]): Query<Row, Row[]>
  insert(rows: Partial<Row> | Partial<Row>[]): Query<Row, Row> | Query<Row, Row[]> {
    this.method = 'POST'
    this.body = rows
    return this as unknown as Query<Row, Row>
  }

  /** Needs at least one filter; the server refuses to change every row by accident. Returns the changed rows. */
  update(changes: Partial<Row>): Query<Row, Row[]> {
    this.method = 'PATCH'
    this.body = changes
    return this as unknown as Query<Row, Row[]>
  }

  /** Needs at least one filter. Returns the deleted rows. */
  delete(): Query<Row, Row[]> {
    this.method = 'DELETE'
    return this as unknown as Query<Row, Row[]>
  }

  eq(column: string, value: Value): this { return this.filter(column, 'eq', value) }
  neq(column: string, value: Value): this { return this.filter(column, 'neq', value) }
  gt(column: string, value: Value): this { return this.filter(column, 'gt', value) }
  gte(column: string, value: Value): this { return this.filter(column, 'gte', value) }
  lt(column: string, value: Value): this { return this.filter(column, 'lt', value) }
  lte(column: string, value: Value): this { return this.filter(column, 'lte', value) }
  /** `*` is the wildcard: like('title', 'Intro*') */
  like(column: string, pattern: string): this { return this.filter(column, 'like', pattern) }
  ilike(column: string, pattern: string): this { return this.filter(column, 'ilike', pattern) }
  in(column: string, values: Value[]): this { return this.filter(column, 'in', `(${values.join(',')})`) }
  /** is('author_id', null), is('done', true) */
  is(column: string, value: null | boolean): this { return this.filter(column, 'is', String(value)) }
  /** Negates an operator: not('status', 'eq', 'draft') */
  not(column: string, operator: string, value: Value | null): this {
    return this.filter(column, `not.${operator}`, String(value))
  }

  order(column: string, options: { ascending?: boolean; nulls?: 'first' | 'last' } = {}): this {
    const term = column + (options.ascending === false ? '.desc' : '') + (options.nulls ? `.nulls${options.nulls}` : '')
    const existing = this.params.find(([name]) => name === 'order')
    if (existing) {
      existing[1] += ',' + term
    } else {
      this.params.push(['order', term])
    }
    return this
  }

  limit(count: number): this {
    this.params.push(['limit', String(count)])
    return this
  }

  offset(count: number): this {
    this.params.push(['offset', String(count)])
    return this
  }

  /** The path and query string this builder will request. Exposed for tests and curiosity. */
  toPath(): string {
    // URLSearchParams does the percent-encoding, so a value can contain anything.
    const query = new URLSearchParams(this.params).toString()
    return `/api/v1/${encodeURIComponent(this.table)}${query ? '?' + query : ''}`
  }

  /** Called by `await`. Sends the request; retries once with a fresh token if the server says the token expired. */
  then<A = Result<Out>, B = never>(
    resolve?: ((value: Result<Out>) => A | PromiseLike<A>) | null,
    reject?: ((reason: unknown) => B | PromiseLike<B>) | null,
  ): PromiseLike<A | B> {
    return this.send().then(resolve, reject)
  }

  private filter(column: string, operator: string, value: Value): this {
    this.params.push([column, `${operator}.${value}`])
    return this
  }

  private async send(): Promise<Result<Out>> {
    const { auth } = this.transport
    try {
      let response = await this.request(await auth.accessToken())
      if (response.status === 401 && auth.user) {
        const error = await response.clone().json().catch(() => null)
        if (error?.error?.code === 'invalid_token') {
          response = await this.request(await auth.accessToken(true))
        }
      }
      const text = await response.text()
      const body = text ? JSON.parse(text) : null
      if (!response.ok) {
        return fail(response.status, body?.error?.code ?? 'error', body?.error?.message ?? response.statusText)
      }
      const total = response.headers.get('x-total-count')
      return ok(body as Out, total === null ? null : Number(total))
    } catch (e) {
      return fail(0, 'network', String(e))
    }
  }

  private request(token: string | null): Promise<Response> {
    const headers: Record<string, string> = { apikey: this.transport.anonKey }
    if (token) {
      headers.authorization = `Bearer ${token}`
    }
    if (this.body !== undefined) {
      headers['content-type'] = 'application/json'
    }
    if (this.wantCount) {
      headers.prefer = 'count=exact'
    }
    return this.transport.fetch(this.transport.url + this.toPath(), {
      method: this.method,
      headers,
      body: this.body === undefined ? undefined : JSON.stringify(this.body),
    })
  }
}
