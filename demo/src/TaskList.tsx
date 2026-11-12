import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { db } from './db.ts'

interface Task {
  id: string
  created_at: string
  title: string
  done: boolean
  author_name?: string | null
}

interface Props {
  table: string
  title: string
  subtitle: string
  /** True for the shared board: send and show author names. */
  showAuthors: boolean
  displayName: string
}

// Put a row at the top, or replace the existing copy with the same id.
// Applying the same row twice gives the same list, so a REST answer and the
// live event for the same change never produce a duplicate.
function upsert(rows: Task[], row: Task): Task[] {
  return rows.some((r) => r.id === row.id) ? rows.map((r) => (r.id === row.id ? row : r)) : [row, ...rows]
}

export function TaskList({ table, title, subtitle, showAuthors, displayName }: Props) {
  const [rows, setRows] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [newTitle, setNewTitle] = useState('')

  const load = useCallback(async () => {
    const { data, error } = await db
      .from<Task>(table)
      .select()
      .order('created_at', { ascending: false })
      .limit(100)
    if (error) setError(error.message)
    else setRows(data ?? [])
    setLoading(false)
  }, [table])

  useEffect(() => {
    void load()
    // Other people's changes arrive here; ours arrive too, hence the idempotent upsert.
    const stop = db.subscribe<Task>(table, {
      onEvent: (e) => {
        if (e.op === 'delete') setRows((prev) => prev.filter((r) => r.id !== e.id))
        else if (e.row) setRows((prev) => upsert(prev, e.row as Task))
      },
      // The server says we may have missed changes, so start over from a fresh load.
      onResync: () => void load(),
      onError: (_code, message) => setError(message),
    })
    return stop // unsubscribe when the component unmounts
  }, [table, load])

  async function add(e: FormEvent) {
    e.preventDefault()
    const text = newTitle.trim()
    if (!text) return
    setError(null)
    // `done` must be sent: the table has no default. The owner/author id is
    // filled in by the server, so we do not send it.
    const row: Partial<Task> = { title: text, done: false }
    if (showAuthors && displayName.trim()) row.author_name = displayName.trim()
    const { data, error } = await db.from<Task>(table).insert(row)
    if (error) return setError(error.message)
    setRows((prev) => upsert(prev, data))
    setNewTitle('')
  }

  async function toggle(task: Task) {
    setError(null)
    const { data, error } = await db.from<Task>(table).update({ done: !task.done }).eq('id', task.id)
    if (error) return setError(error.message)
    setRows((prev) => data.reduce(upsert, prev))
  }

  async function remove(task: Task) {
    setError(null)
    const { error } = await db.from<Task>(table).delete().eq('id', task.id)
    if (error) return setError(error.message)
    setRows((prev) => prev.filter((r) => r.id !== task.id))
  }

  return (
    <section className="column">
      <h2>{title}</h2>
      <p className="muted small">{subtitle}</p>
      <form className="add" onSubmit={add}>
        <input
          type="text"
          placeholder="Add a task"
          aria-label={`Add a task to ${title}`}
          maxLength={200}
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <button className="primary" type="submit">
          Add
        </button>
      </form>
      {error && <p className="error small">{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="muted">No tasks yet</p>
      ) : (
        <ul className="tasks">
          {rows.map((task) => (
            <li key={task.id}>
              <input type="checkbox" checked={task.done} onChange={() => void toggle(task)} />
              <span className={task.done ? 'title done' : 'title'}>
                {task.title}
                {showAuthors && task.author_name && (
                  <span className="muted small"> by {task.author_name}</span>
                )}
              </span>
              <button className="link" onClick={() => void remove(task)}>
                delete
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
