# tsukuyomi-js

A small client for a Tsukuyomi project. One file per concern, no dependencies:

| File | What it does |
|---|---|
| `src/index.ts` | `createClient(url, anonKey)`: the entry point |
| `src/query.ts` | `db.from('table')`: builds the REST request step by step |
| `src/auth.ts` | `db.auth`: sign up, log in, keep the login fresh |
| `src/live.ts` | `db.subscribe('table', …)`: live updates with reconnect and replay |

```ts
import { createClient } from './sdk/src/index.ts'

const db = createClient('https://tsukuyomi-l9kg.onrender.com', '<anon key>')

await db.auth.signUp('ada@example.com', 'a long password')

type Task = { id: string; title: string; done: boolean }
const { data, error } = await db.from<Task>('tasks').select('id,title,done').eq('done', false).limit(20)
await db.from<Task>('tasks').insert({ title: 'milk', done: false })
await db.from<Task>('tasks').update({ done: true }).eq('id', data![0].id)

const stop = db.subscribe<Task>('tasks', {
  onEvent: (e) => console.log(e.op, e.id, e.row),
  onResync: () => console.log('load the table again'),
})
```

- Calls never throw when the server refuses something. They return `{ data, error }`; check `error`.
- Use the **anon** key only. The service key bypasses permission rules and must stay on a server.
- It is imported by path (see `demo/`), not published to npm.

Run the tests: `npm install && npm test` (Node 24; they use a fake `fetch` and a fake `WebSocket`).
