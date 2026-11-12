// Measures a running Tsukuyomi server: requests per second and latency for
// reads and writes, and how long a change takes to reach a live subscriber.
//
//   node scripts/bench.mjs <server url> [seconds per test] [parallel requests]
//
// It creates its own account and project, and deletes the project afterwards.
// Start the server with tsukuyomi.rate-limit.enabled=false, or the limiter
// (correctly) refuses most of this.
const [url, secondsArg = '10', parallelArg = '16'] = process.argv.slice(2)
if (!url) {
  console.error('usage: node scripts/bench.mjs <server url> [seconds per test] [parallel requests]')
  process.exit(2)
}
const seconds = Number(secondsArg), parallel = Number(parallelArg)

let cookie = ''
async function platform(method, path, body) {
  const r = await fetch(url + path, { method, headers: { 'content-type': 'application/json', ...(cookie ? { cookie } : {}) }, body: body ? JSON.stringify(body) : undefined })
  const set = r.headers.get('set-cookie'); if (set) cookie = set.split(';')[0]
  const text = await r.text()
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${text}`)
  return text ? JSON.parse(text) : null
}

const stamp = Date.now()
const account = { email: `bench-${stamp}@example.com`, password: 'benchmark password' }
await platform('POST', '/platform/v1/accounts', account)
await platform('POST', '/platform/v1/session', account)
const created = await platform('POST', '/platform/v1/projects', { name: 'bench' })
const key = created.serviceKey, anon = created.anonKey
const api = (method, path, body, headers = {}) => fetch(url + path, { method, headers: { apikey: key, 'content-type': 'application/json', ...headers }, body: body ? JSON.stringify(body) : undefined })

await platform('POST', `/platform/v1/projects/${created.project.id}/migrations`, { operations: [
  { op: 'create_table', table: 'authors', columns: [{ name: 'name', type: 'text' }] },
  { op: 'create_table', table: 'posts', columns: [
    { name: 'title', type: 'text' }, { name: 'priority', type: 'integer' },
    { name: 'author_id', type: 'uuid', references: 'authors', onDelete: 'cascade' },
    { name: 'owner_id', type: 'uuid', references: 'users', onDelete: 'cascade' }] },
  { op: 'set_rule', table: 'posts', operation: 'select', rule: 'owner', ownerColumn: 'owner_id' },
  { op: 'create_table', table: 'scratch', columns: [{ name: 'n', type: 'integer' }] },
] })

// Data: 20 authors, 2,000 posts; half of the posts belong to one end user.
const signup = await (await fetch(url + '/auth/v1/signup', { method: 'POST', headers: { apikey: anon, 'content-type': 'application/json' }, body: JSON.stringify({ email: `user-${stamp}@example.com`, password: 'benchmark password' }) })).json()
const authors = await (await api('POST', '/api/v1/authors', Array.from({ length: 20 }, (_, i) => ({ name: `author ${i}` })))).json()
for (let batch = 0; batch < 20; batch++) {
  const rows = Array.from({ length: 100 }, (_, i) => ({ title: `post ${batch * 100 + i}`, priority: i % 5, author_id: authors[i % 20].id, owner_id: i % 2 ? signup.user.id : null }))
  const r = await api('POST', '/api/v1/posts', rows)
  if (!r.ok) throw new Error('seed: ' + r.status + ' ' + await r.text())
}

/** Runs `request` from `parallel` loops for `seconds` (or until maxRequests); returns throughput and latency percentiles. */
async function measure(name, request, maxRequests = Infinity) {
  const times = []; let errors = 0, sent = 0
  const end = performance.now() + seconds * 1000
  const started = performance.now()
  await Promise.all(Array.from({ length: parallel }, async () => {
    while (performance.now() < end && sent++ < maxRequests) {
      const t = performance.now()
      const r = await request()
      await r.arrayBuffer()
      if (!r.ok) errors++
      times.push(performance.now() - t)
    }
  }))
  const elapsed = (performance.now() - started) / 1000
  times.sort((a, b) => a - b)
  const at = (p) => times[Math.min(times.length - 1, Math.floor(times.length * p))].toFixed(1)
  const line = { test: name, 'req/s': Math.round(times.length / elapsed), 'p50 ms': at(0.5), 'p95 ms': at(0.95), 'p99 ms': at(0.99), errors }
  console.log(JSON.stringify(line))
  return line
}

const userHeaders = { apikey: anon, authorization: 'Bearer ' + signup.access_token }
const results = []
// Warm up the JIT compiler and the connection pools; not reported.
const quiet = console.log; console.log = () => {}
await measure('warm-up', () => api('GET', '/api/v1/posts?limit=20'))
console.log = quiet
results.push(await measure('read 20 rows (service key)', () => api('GET', '/api/v1/posts?limit=20')))
results.push(await measure('read 20 rows as a user (row-level security filters 2,000 rows to 1,000)', () => fetch(url + '/api/v1/posts?limit=20', { headers: userHeaders })))
results.push(await measure('filter + sort + expand author', () => api('GET', '/api/v1/posts?select=title,priority,authors(name)&priority=gte.2&order=priority.desc,title&limit=20')))
// A fixed number, because a table holds at most 10,000 rows (the row quota).
results.push(await measure('insert one row (5,000 inserts into one table)', () => api('POST', '/api/v1/scratch', { n: 1 }), 5000))

// Live update delay: time from sending an insert to receiving its event.
const delays = []
await new Promise((resolve, reject) => {
  const ws = new WebSocket(url.replace(/^http/, 'ws') + '/realtime/v1')
  let sentAt = 0, left = 200
  const next = () => { sentAt = performance.now(); api('POST', '/api/v1/authors', { name: 'live' }).then((r) => r.arrayBuffer()) }
  ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', apikey: key }))
  ws.onerror = reject
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data)
    if (msg.type === 'ready') ws.send(JSON.stringify({ type: 'subscribe', table: 'authors' }))
    else if (msg.type === 'subscribed') next()
    else if (msg.type === 'event') {
      delays.push(performance.now() - sentAt)
      if (--left === 0) { ws.close(); resolve() } else next()
    }
  }
})
delays.sort((a, b) => a - b)
const live = { test: 'live update: insert sent -> event received (200 samples)', 'p50 ms': delays[100].toFixed(1), 'p95 ms': delays[190].toFixed(1), 'p99 ms': delays[198].toFixed(1) }
console.log(JSON.stringify(live))

await platform('DELETE', `/platform/v1/projects/${created.project.id}`)
console.log(`done: ${seconds}s per test, ${parallel} parallel requests, project deleted`)
