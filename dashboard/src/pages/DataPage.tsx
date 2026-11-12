import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { api, errorMessage } from '../api.ts'
import type { Column, Row, RowFilter, Table } from '../api.ts'

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
          <NavLink key={t.name} to={`/projects/${projectId}/data/${t.name}`} className={t.name === table.name ? 'active' : ''}>
            {t.name}
          </NavLink>
        ))}
      </nav>
      {/* key: start with fresh paging and form state when the table changes */}
      <Rows key={table.name} projectId={projectId} table={table} />
    </div>
  )
}

const OPERATORS = ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'ilike', 'is']

function Rows({ projectId, table }: { projectId: string; table: Table }) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState<RowFilter | undefined>()
  const [order, setOrder] = useState<string | undefined>() // e.g. 'title.asc'
  const rows = useQuery({
    queryKey: ['rows', projectId, table.name, filter, order, page],
    queryFn: () => api.rows(projectId, table.name, { limit: PAGE_SIZE, offset: page * PAGE_SIZE, order, filter }),
  })

  const writable = table.columns.filter((c) => !c.system)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [editingId, setEditingId] = useState<string | null>(null)

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['rows', projectId, table.name] })

  const insert = useMutation({
    mutationFn: (row: Row) => api.insertRow(projectId, table.name, row),
    onSuccess: () => {
      setDraft({})
      return refresh()
    },
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteRow(projectId, table.name, id),
    onSuccess: refresh,
  })

  const update = useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: Row }) => api.updateRow(projectId, table.name, id, changes),
    onSuccess: () => {
      setEditingId(null)
      return refresh()
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

  // asc -> desc -> none
  function toggleOrder(column: string) {
    setOrder(order === `${column}.asc` ? `${column}.desc` : order === `${column}.desc` ? undefined : `${column}.asc`)
    setPage(0)
  }

  function applyFilter(next: RowFilter | undefined) {
    setFilter(next)
    setPage(0)
  }

  const total = rows.data?.total ?? null
  const hasNext = total === null ? (rows.data?.rows.length ?? 0) === PAGE_SIZE : (page + 1) * PAGE_SIZE < total

  return (
    <div className="stack">
      <FilterBar columns={table.columns} onApply={applyFilter} />
      {rows.isError && <p className="error">{errorMessage(rows.error)}</p>}
      {remove.isError && <p className="error">{errorMessage(remove.error)}</p>}
      {update.isError && <p className="error">{errorMessage(update.error)}</p>}
      <div className="scroll">
        <table>
          <thead>
            <tr>
              {table.columns.map((c) => (
                <th key={c.name}>
                  {c.type === 'jsonb' ? c.name : (
                    <button className="link" onClick={() => toggleOrder(c.name)}>
                      {c.name}{order === `${c.name}.asc` ? ' ▲' : order === `${c.name}.desc` ? ' ▼' : ''}
                    </button>
                  )}
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.data?.rows.map((row) => {
              const id = String(row.id)
              return id === editingId ? (
                <EditRow
                  key={id} columns={table.columns} row={row} busy={update.isPending}
                  onSave={(changes) => update.mutate({ id, changes })}
                  onCancel={() => setEditingId(null)}
                />
              ) : (
                <tr key={id}>
                  {table.columns.map((c) => <td key={c.name}>{display(row[c.name])}</td>)}
                  <td>
                    <button className="link" onClick={() => { update.reset(); setEditingId(id) }}>edit</button>
                    <button
                      className="link danger" disabled={remove.isPending}
                      onClick={() => window.confirm('Delete this row?') && remove.mutate(id)}
                    >
                      delete
                    </button>
                  </td>
                </tr>
              )
            })}
            {rows.data?.rows.length === 0 && (
              <tr><td colSpan={table.columns.length + 1} className="muted">No rows{page > 0 ? ' on this page' : ' yet'}.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="row">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span className="muted">page {page + 1}</span>
        <button disabled={!hasNext} onClick={() => setPage(page + 1)}>Next</button>
        {total !== null && <span className="muted">{total} rows</span>}
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

function FilterBar({ columns, onApply }: { columns: Column[]; onApply: (filter: RowFilter | undefined) => void }) {
  const filterable = columns.filter((c) => c.type !== 'jsonb')
  const [column, setColumn] = useState(filterable[0]?.name ?? '')
  const [op, setOp] = useState('eq')
  const [value, setValue] = useState('')

  function apply() {
    // ilike needs wildcards to behave like "contains"; add them unless the user typed their own
    const text = op === 'ilike' && !value.includes('*') ? `*${value}*` : value
    onApply({ column, op, value: text })
  }

  function clear() {
    setValue('')
    onApply(undefined)
  }

  return (
    <div className="row">
      <select value={column} onChange={(e) => setColumn(e.target.value)}>
        {filterable.map((c) => <option key={c.name}>{c.name}</option>)}
      </select>
      <select value={op} onChange={(e) => setOp(e.target.value)}>
        {OPERATORS.map((o) => <option key={o}>{o}</option>)}
      </select>
      <input
        className="grow" value={value} placeholder={op === 'is' ? 'null, true or false' : 'value'}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && apply()}
      />
      <button disabled={!column || value === ''} onClick={apply}>Apply</button>
      <button onClick={clear}>Clear</button>
    </div>
  )
}

/** One row turned into inputs. Only the cells that differ from the stored value are sent. */
function EditRow({ columns, row, busy, onSave, onCancel }: {
  columns: Column[]
  row: Row
  busy: boolean
  onSave: (changes: Row) => void
  onCancel: () => void
}) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(columns.filter((c) => !c.system).map((c) => [c.name, display(row[c.name])])),
  )

  function save() {
    const changes: Row = {}
    for (const column of columns.filter((c) => !c.system)) {
      const text = values[column.name]
      if (text === display(row[column.name])) {
        continue
      }
      if (text === '' && column.nullable) {
        changes[column.name] = null
      } else {
        changes[column.name] = column.type === 'jsonb' ? parseJson(text) : text
      }
    }
    if (Object.keys(changes).length === 0) {
      onCancel() // nothing was edited, so there is nothing to send
      return
    }
    onSave(changes)
  }

  return (
    <tr>
      {columns.map((c) => (
        <td key={c.name}>
          {c.system ? display(row[c.name]) : (
            <input
              value={values[c.name]} placeholder={placeholder(c.type)}
              onChange={(e) => setValues({ ...values, [c.name]: e.target.value })}
            />
          )}
        </td>
      ))}
      <td>
        <button className="link" disabled={busy} onClick={save}>save</button>
        <button className="link" onClick={onCancel}>cancel</button>
      </td>
    </tr>
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
