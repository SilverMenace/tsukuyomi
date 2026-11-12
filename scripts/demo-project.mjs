// Creates the project behind the demo app: two tables and their rules.
//
//   node scripts/demo-project.mjs <server url> <owner email> <owner password>
//
// It logs in to the dashboard API (or signs up if the account does not exist),
// creates a project called "demo" and prints its anon key, which goes into
// demo/src/config.ts. The anon key is public by design: what it may do is
// decided by the rules below and enforced by the database.
const [url, email, password] = process.argv.slice(2)
if (!url || !email || !password) {
  console.error('usage: node scripts/demo-project.mjs <server url> <owner email> <owner password>')
  process.exit(2)
}

let cookie = ''
async function call(method, path, body) {
  const response = await fetch(url + path, {
    method,
    headers: { 'content-type': 'application/json', ...(cookie ? { cookie } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const setCookie = response.headers.get('set-cookie')
  if (setCookie) cookie = setCookie.split(';')[0]
  const text = await response.text()
  return { status: response.status, body: text ? JSON.parse(text) : null }
}
function must(what, result, ...statuses) {
  if (!statuses.includes(result.status)) {
    console.error(`${what} failed: ${result.status} ${JSON.stringify(result.body)}`)
    process.exit(1)
  }
  return result.body
}

let login = await call('POST', '/platform/v1/session', { email, password })
if (login.status === 401) {
  must('sign up', await call('POST', '/platform/v1/accounts', { email, password }), 200, 201)
  login = await call('POST', '/platform/v1/session', { email, password })
}
must('log in', login, 200, 204)

const created = must('create project', await call('POST', '/platform/v1/projects', { name: 'demo' }), 201)

const owner = (table, column, operations) =>
  operations.map((operation) => ({ op: 'set_rule', table, operation, rule: 'owner', ownerColumn: column }))
const everyoneLoggedIn = (table, operations) =>
  operations.map((operation) => ({ op: 'set_rule', table, operation, rule: 'authenticated' }))

const operations = [
  // Private: each row belongs to one user and nobody else can see or touch it.
  { op: 'create_table', table: 'private_tasks', columns: [
    { name: 'title', type: 'text', nullable: false },
    { name: 'done', type: 'boolean', nullable: false },
    { name: 'owner_id', type: 'uuid', nullable: false, references: 'users', onDelete: 'cascade' },
  ] },
  ...owner('private_tasks', 'owner_id', ['select', 'insert', 'update', 'delete']),

  // Shared: every logged-in user sees and changes everything. Inserting uses
  // the owner rule so that author_id is always the real author.
  { op: 'create_table', table: 'shared_tasks', columns: [
    { name: 'title', type: 'text', nullable: false },
    { name: 'done', type: 'boolean', nullable: false },
    { name: 'author_name', type: 'text' },
    { name: 'author_id', type: 'uuid', nullable: false, references: 'users', onDelete: 'cascade' },
  ] },
  ...owner('shared_tasks', 'author_id', ['insert']),
  ...everyoneLoggedIn('shared_tasks', ['select', 'update', 'delete']),
]
must('apply schema', await call('POST', `/platform/v1/projects/${created.project.id}/migrations`, { operations }), 200)

console.log(JSON.stringify({ projectId: created.project.id, anonKey: created.anonKey, serviceKey: created.serviceKey }))
