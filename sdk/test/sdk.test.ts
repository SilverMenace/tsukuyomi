import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createClient } from '../src/index.ts'

const KEY = 'tsk_anon_0123456789abcdef'

/** A fetch that records requests and answers from a queue. */
function fakeFetch(answers: Array<{ status: number; body?: unknown; headers?: Record<string, string> }>) {
  const calls: Array<{ url: string; method: string; headers: Record<string, string>; body: unknown }> = []
  const fetch = async (url: any, init: any = {}) => {
    calls.push({ url: String(url), method: init.method ?? 'GET', headers: init.headers ?? {}, body: init.body ? JSON.parse(init.body) : undefined })
    const answer = answers.shift() ?? { status: 500, body: { error: { code: 'test', message: 'no answer queued' } } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), { status: answer.status, headers: answer.headers })
  }
  return { fetch: fetch as typeof globalThis.fetch, calls }
}

const tokens = (n: number, expiresIn = 3600) => ({
  access_token: `access-${n}`, refresh_token: `refresh-${n}`, token_type: 'bearer', expires_in: expiresIn,
  user: { id: 'u1', email: 'ada@example.com' },
})

/** A WebSocket that tests drive by hand. */
class FakeSocket {
  static all: FakeSocket[] = []
  readyState = 0
  sent: any[] = []
  onopen: (() => void) | null = null
  onmessage: ((m: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  url: string
  constructor(url: string) { this.url = url; FakeSocket.all.push(this) }
  send(text: string) { this.sent.push(JSON.parse(text)) }
  close() { this.readyState = 3 }
  // test helpers
  async open() { this.readyState = 1; this.onopen?.(); await tick() }
  say(message: object) { this.onmessage?.({ data: JSON.stringify(message) }) }
  drop() { this.readyState = 3; this.onclose?.() }
}
const tick = () => new Promise((r) => setTimeout(r, 0))
const last = () => FakeSocket.all[FakeSocket.all.length - 1]

test('a query builder produces the documented URL', () => {
  const db = createClient('https://host/', KEY, { storage: null })
  const path = db.from('posts').select('title,authors(name)').gte('priority', 2).not('status', 'eq', 'draft')
    .in('tag', ['a', 'b']).is('author_id', null).order('priority', { ascending: false, nulls: 'last' }).order('title')
    .limit(10).offset(20).toPath()
  assert.equal(decodeURIComponent(path.replaceAll('+', ' ')),
    '/api/v1/posts?select=title,authors(name)&priority=gte.2&status=not.eq.draft&tag=in.(a,b)&author_id=is.null&order=priority.desc.nullslast,title&limit=10&offset=20')
})

test('values are encoded, so they cannot add parameters of their own', () => {
  const db = createClient('https://host', KEY, { storage: null })
  const path = db.from('posts').select().eq('title', 'a&limit=1000#x').toPath()
  assert.equal(new URLSearchParams(path.split('?')[1]).get('title'), 'eq.a&limit=1000#x')
  assert.equal(new URLSearchParams(path.split('?')[1]).get('limit'), null)
})

test('requests carry the key, results and errors come back as { data, error }', async () => {
  const { fetch, calls } = fakeFetch([
    { status: 200, body: [{ id: '1' }], headers: { 'x-total-count': '7' } },
    { status: 403, body: { error: { code: 'forbidden', message: 'a permission rule refused the write' } } },
  ])
  const db = createClient('https://host', KEY, { storage: null, fetch })
  const read = await db.from('tasks').select('*', { count: true })
  assert.deepEqual(read, { data: [{ id: '1' }], error: null, count: 7 })
  assert.equal(calls[0].headers.apikey, KEY)
  assert.equal(calls[0].headers.prefer, 'count=exact')
  assert.equal(calls[0].headers.authorization, undefined)

  const write = await db.from('tasks').insert({ title: 'x' })
  assert.equal(write.data, null)
  assert.deepEqual(write.error, { status: 403, code: 'forbidden', message: 'a permission rule refused the write' })
  assert.deepEqual(calls[1].body, { title: 'x' })
})

test('an expired token is refreshed once and the request repeated', async () => {
  const { fetch, calls } = fakeFetch([
    { status: 200, body: tokens(1) },                                                  // login
    { status: 401, body: { error: { code: 'invalid_token', message: 'expired' } } },    // first try
    { status: 200, body: tokens(2) },                                                  // refresh
    { status: 200, body: [] },                                                         // second try
  ])
  const db = createClient('https://host', KEY, { storage: null, fetch })
  await db.auth.signIn('ada@example.com', 'pw')
  const result = await db.from('tasks').select()
  assert.deepEqual(result.data, [])
  assert.equal(calls[1].headers.authorization, 'Bearer access-1')
  assert.deepEqual(calls[2].body, { refresh_token: 'refresh-1' })
  assert.equal(calls[3].headers.authorization, 'Bearer access-2')
})

test('many requests at once share one refresh; a refused refresh logs out', async () => {
  const { fetch, calls } = fakeFetch([
    { status: 200, body: tokens(1, 30) },   // login with a token that is about to expire
    { status: 200, body: tokens(2) },       // exactly one refresh
  ])
  const db = createClient('https://host', KEY, { storage: null, fetch })
  await db.auth.signIn('ada@example.com', 'pw')
  const got = await Promise.all([db.auth.accessToken(), db.auth.accessToken(), db.auth.accessToken()])
  assert.deepEqual(got, ['access-2', 'access-2', 'access-2'])
  assert.equal(calls.filter((c) => c.url.endsWith('/refresh')).length, 1)

  const changes: Array<string | null> = []
  db.auth.onChange((user) => changes.push(user?.id ?? null))
  // The next forced refresh is refused by the server (queue is empty -> 500).
  assert.equal(await db.auth.accessToken(true), null)
  assert.equal(db.auth.user, null)
  assert.deepEqual(changes, [null])
})

test('the session survives a page reload through storage', async () => {
  const memory = new Map<string, string>()
  const storage = { getItem: (k: string) => memory.get(k) ?? null, setItem: (k: string, v: string) => void memory.set(k, v), removeItem: (k: string) => void memory.delete(k) }
  const { fetch } = fakeFetch([{ status: 200, body: tokens(1) }, { status: 204 }])
  await createClient('https://host', KEY, { storage, fetch }).auth.signIn('ada@example.com', 'pw')
  const reloaded = createClient('https://host', KEY, { storage, fetch })
  assert.equal(reloaded.auth.user?.email, 'ada@example.com')
  await reloaded.auth.signOut()
  assert.equal(memory.size, 0)
})

test('live: authenticates first, delivers events, skips repeats', async () => {
  FakeSocket.all = []
  const db = createClient('https://host', KEY, { storage: null, WebSocket: FakeSocket as never })
  const events: string[] = []
  const stop = db.subscribe('tasks', { onEvent: (e) => events.push(`${e.op}:${e.id}`) })
  const socket = last()
  assert.equal(socket.url, 'wss://host/realtime/v1')
  await socket.open()
  assert.deepEqual(socket.sent, [{ type: 'auth', apikey: KEY }])
  socket.say({ type: 'ready' })
  assert.deepEqual(socket.sent[1], { type: 'subscribe', table: 'tasks' })
  socket.say({ type: 'subscribed', table: 'tasks', cursor: '10', resync: false })
  socket.say({ type: 'event', table: 'tasks', op: 'insert', id: 'r1', row: {}, eventId: '1', cursor: '11' })
  socket.say({ type: 'event', table: 'tasks', op: 'insert', id: 'r1', row: {}, eventId: '1', cursor: '11' })
  socket.say({ type: 'event', table: 'other', op: 'insert', id: 'r9', row: {}, eventId: '2', cursor: '11' })
  assert.deepEqual(events, ['insert:r1'])
  stop()
  assert.deepEqual(socket.sent[2], { type: 'unsubscribe', table: 'tasks' })
  assert.equal(socket.readyState, 3) // last subscription gone: connection closed
})

test('live: after a dropped connection it resubscribes from the last cursor', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  FakeSocket.all = []
  const db = createClient('https://host', KEY, { storage: null, WebSocket: FakeSocket as never })
  let resyncs = 0
  const events: string[] = []
  db.subscribe('tasks', { onEvent: (e) => events.push(e.id), onResync: () => resyncs++ })
  const first = last()
  first.readyState = 1; first.onopen?.(); await Promise.resolve(); await Promise.resolve()
  first.say({ type: 'ready' })
  first.say({ type: 'subscribed', table: 'tasks', cursor: '10', resync: false })
  first.say({ type: 'event', table: 'tasks', op: 'insert', id: 'r1', row: {}, eventId: '1', cursor: '15' })
  first.drop()

  t.mock.timers.tick(1000)
  const second = last()
  assert.notEqual(second, first)
  second.readyState = 1; second.onopen?.(); await Promise.resolve(); await Promise.resolve()
  second.say({ type: 'ready' })
  assert.deepEqual(second.sent[1], { type: 'subscribe', table: 'tasks', since: '15' })
  second.say({ type: 'subscribed', table: 'tasks', cursor: '15', resync: false })
  // Replay repeats r1 (already seen) and brings r2 (missed).
  second.say({ type: 'event', table: 'tasks', op: 'insert', id: 'r1', row: {}, eventId: '1', cursor: '15' })
  second.say({ type: 'event', table: 'tasks', op: 'insert', id: 'r2', row: {}, eventId: '2', cursor: '16' })
  assert.deepEqual(events, ['r1', 'r2'])
  assert.equal(resyncs, 0)

  // The server says it cannot replay: the app is told to reload.
  second.say({ type: 'resync', table: 'tasks', cursor: '40' })
  assert.equal(resyncs, 1)
})

test('live: a rejected token gets a fresh one on a new connection', async () => {
  FakeSocket.all = []
  const { fetch } = fakeFetch([{ status: 200, body: tokens(1) }, { status: 200, body: tokens(2) }])
  const db = createClient('https://host', KEY, { storage: null, fetch, WebSocket: FakeSocket as never })
  await db.auth.signIn('ada@example.com', 'pw')
  db.subscribe('tasks', { onEvent: () => undefined })
  const first = last()
  await first.open()
  assert.equal(first.sent[0].token, 'access-1')
  first.say({ type: 'error', code: 'token_expired', message: 'expired' })
  const second = last()
  assert.notEqual(second, first)
  assert.equal(first.readyState, 3) // closed by us, without waiting for the server
  await second.open()
  await tick()
  assert.equal(second.sent[0].token, 'access-2')
})

test('live: logging in reconnects as the new identity and asks subscribers to reload', async () => {
  FakeSocket.all = []
  const { fetch } = fakeFetch([{ status: 200, body: tokens(1) }])
  const db = createClient('https://host', KEY, { storage: null, fetch, WebSocket: FakeSocket as never })
  let resyncs = 0
  db.subscribe('tasks', { onEvent: () => undefined, onResync: () => resyncs++ })
  const anonymous = last()
  await anonymous.open()
  anonymous.say({ type: 'ready' })
  anonymous.say({ type: 'subscribed', table: 'tasks', cursor: '10', resync: false })

  await db.auth.signIn('ada@example.com', 'pw')
  const asUser = last()
  assert.notEqual(asUser, anonymous)
  await asUser.open()
  assert.equal(asUser.sent[0].token, 'access-1')
  asUser.say({ type: 'ready' })
  assert.deepEqual(asUser.sent[1], { type: 'subscribe', table: 'tasks' }) // no cursor from the old identity
  asUser.say({ type: 'subscribed', table: 'tasks', cursor: '12', resync: false })
  assert.equal(resyncs, 1)
})
