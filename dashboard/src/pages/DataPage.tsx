import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { api, errorMessage } from '../api.ts'
import type { Row, Table } from '../api.ts'

const PAGE_SIZE = 25

/** Browse and add rows. Uses the same row endpoints as the public API, as the service role. */
export default function DataPage({ projectId }: { projectId: string }) {
  const { table: tableName } = useParams()
  const schema = useQuery({ queryKey: ['schema', projectId], queryFn: () => api.schema(projectId) })

  if (schema.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (schema.isError) {
    return <p className="error">{errorMessage(schema.error)}</p>
  }
  if (schema.data.tables.length === 0) {
    return <p className="muted">Create a table first, on the Tables tab.</p>
  }
  const table = schema.data.tables.find((t) => t.name === tableName) ?? schema.data.tables[0]

  return (
    <div className="stack">
      <nav className="pills">
        {schema.data.tables.map((t) => (
          <NavLink key={t.name} to={`../data/${t.name}`} className={t.name === table.name ? 'active' : ''}>
            {t.name}
          </NavLink>
        ))}
      </nav>
      {/* key: start with fresh paging and form state when the table changes */}
      <Rows key={table.name} projectId={projectId} table={table} />
    </div>
  )
}

function Rows({ projectId, table }: { projectId: string; table: Table }) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const rows = useQuery({
    queryKey: ['rows', projectId, table.name, page],
    queryFn: () => api.rows(projectId, table.name, PAGE_SIZE, page * PAGE_SIZE),
  })

  const writable = table.columns.filter((c) => !c.system)
  const [draft, setDraft] = useState<Record<string, string>>({})

  const insert = useMutation({
    mutationFn: (row: Row) => api.insertRow(projectId, table.name, row),
    onSuccess: () => {
      setDraft({})
      return queryClient.invalidateQueries({ queryKey: ['rows', projectId, table.name] })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const row: Row = {}
    for (const column of writable) {
      const text = draft[column.name] ?? ''
      if (text === '') {
        continue // left empty: let the database use NULL or complain if required
      }
      row[column.name] = column.type === 'jsonb' ? parseJson(text) : text
    }
    insert.mutate(row)
  }

  return (
    <div className="stack">
      {rows.isError && <p className="error">{errorMessage(rows.error)}</p>}
      <div className="scroll">
        <table>
          <thead>
            <tr>{table.columns.map((c) => <th key={c.name}>{c.name}</th>)}</tr>
          </thead>
          <tbody>
            {rows.data?.map((row) => (
              <tr key={String(row.id)}>
                {table.columns.map((c) => <td key={c.name}>{display(row[c.name])}</td>)}
              </tr>
            ))}
            {rows.data?.length === 0 && (
              <tr><td colSpan={table.columns.length} className="muted">No rows{page > 0 ? ' on this page' : ' yet'}.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="row">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span className="muted">page {page + 1}</span>
        <button disabled={(rows.data?.length ?? 0) < PAGE_SIZE} onClick={() => setPage(page + 1)}>Next</button>
      </div>

      <form onSubmit={submit} className="card stack">
        <h2>Add a row</h2>
        {writable.length === 0 && <p className="muted">This table has only automatic columns.</p>}
        {writable.map((c) => (
          <label key={c.name}>
            {c.name} <span className="muted small">{c.type}{c.nullable ? '' : ', required'}</span>
            <input
              value={draft[c.name] ?? ''} placeholder={placeholder(c.type)}
              onChange={(e) => setDraft({ ...draft, [c.name]: e.target.value })}
            />
          </label>
        ))}
        {insert.isError && <p className="error">{errorMessage(insert.error)}</p>}
        <div><button disabled={insert.isPending}>Insert</button></div>
      </form>
    </div>
  )
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text // not JSON: store it as a JSON string
  }
}

function display(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function placeholder(type: string): string {
  switch (type) {
    case 'boolean': return 'true or false'
    case 'timestamptz': return '2026-01-31T10:00:00Z'
    case 'date': return '2026-01-31'
    case 'jsonb': return '{"any": "json"}'
    case 'uuid': return '00000000-0000-0000-0000-000000000000'
    default: return ''
  }
}
