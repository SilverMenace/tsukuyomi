import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, COLUMN_TYPES, errorMessage } from '../api.ts'
import type { ColumnSpec, ColumnType, Operation, Table } from '../api.ts'

const emptyColumn = (): ColumnSpec => ({ name: '', type: 'text', nullable: true, unique: false })

/**
 * The table designer. Every change the user makes becomes a list of
 * operations. "Preview SQL" sends them as a dry run; "Apply" sends them for real.
 */
export default function TablesPage({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const schema = useQuery({ queryKey: ['schema', projectId], queryFn: () => api.schema(projectId) })
  const [preview, setPreview] = useState('')

  const migrate = useMutation({
    mutationFn: ({ operations, dryRun }: { operations: Operation[]; dryRun: boolean }) =>
      api.migrate(projectId, operations, dryRun),
    onSuccess: (result) => {
      setPreview(result.applied ? '' : result.sql)
      if (result.applied) {
        return Promise.all([
          queryClient.invalidateQueries({ queryKey: ['schema', projectId] }),
          queryClient.invalidateQueries({ queryKey: ['migrations', projectId] }),
          queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
        ])
      }
    },
  })

  const run = (operations: Operation[], dryRun = false) => migrate.mutate({ operations, dryRun })

  if (schema.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (schema.isError) {
    return <p className="error">{errorMessage(schema.error)}</p>
  }

  return (
    <div className="stack">
      {migrate.isError && <p className="error">{errorMessage(migrate.error)}</p>}
      {preview && (
        <div className="card stack">
          <strong>SQL this would run (nothing has changed yet)</strong>
          <pre>{preview}</pre>
        </div>
      )}

      {schema.data.tables.length === 0 && <p className="muted">No tables yet. Create one below.</p>}
      {schema.data.tables.map((table) => (
        <TableCard key={table.name} table={table} run={run} busy={migrate.isPending} />
      ))}

      <NewTableForm run={run} busy={migrate.isPending} />
    </div>
  )
}

type Run = (operations: Operation[], dryRun?: boolean) => void

function TableCard({ table, run, busy }: { table: Table; run: Run; busy: boolean }) {
  const [column, setColumn] = useState<ColumnSpec>(emptyColumn())

  function addColumn(dryRun: boolean) {
    run([{ op: 'add_column', table: table.name, column }], dryRun)
    if (!dryRun) {
      setColumn(emptyColumn())
    }
  }

  return (
    <div className="card stack">
      <div className="row">
        <h2 className="grow">{table.name}</h2>
        <Link to={`../data/${table.name}`}>Browse rows</Link>
        <button
          className="danger" disabled={busy}
          onClick={() => window.confirm(`Delete table "${table.name}" and all its rows?`) && run([{ op: 'drop_table', table: table.name }])}
        >
          Delete table
        </button>
      </div>
      <table>
        <thead>
          <tr><th>Column</th><th>Type</th><th>Required</th><th>Unique</th><th /></tr>
        </thead>
        <tbody>
          {table.columns.map((c) => (
            <tr key={c.name}>
              <td>{c.name} {c.system && <span className="muted small">(automatic)</span>}</td>
              <td>{c.type}</td>
              <td>{c.nullable ? '' : 'yes'}</td>
              <td>{c.unique ? 'yes' : ''}</td>
              <td>
                {!c.system && (
                  <button
                    className="link danger" disabled={busy}
                    onClick={() => window.confirm(`Delete column "${c.name}" and its data?`) && run([{ op: 'drop_column', table: table.name, column: c.name }])}
                  >
                    remove
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row">
        <ColumnInputs column={column} onChange={setColumn} />
        <button disabled={busy || !column.name} onClick={() => addColumn(true)}>Preview SQL</button>
        <button disabled={busy || !column.name} onClick={() => addColumn(false)}>Add column</button>
      </div>
    </div>
  )
}

function NewTableForm({ run, busy }: { run: Run; busy: boolean }) {
  const [name, setName] = useState('')
  const [columns, setColumns] = useState<ColumnSpec[]>([emptyColumn()])

  const operations = (): Operation[] => [
    { op: 'create_table', table: name, columns: columns.filter((c) => c.name) },
  ]

  function create() {
    run(operations())
    setName('')
    setColumns([emptyColumn()])
  }

  return (
    <div className="card stack">
      <h2>New table</h2>
      <input placeholder="table name, e.g. notes" value={name} onChange={(e) => setName(e.target.value)} />
      <p className="muted small">
        Every table automatically gets <code>id</code> (uuid) and <code>created_at</code>.
        Names: lowercase letters, digits and underscores.
      </p>
      {columns.map((column, index) => (
        <div className="row" key={index}>
          <ColumnInputs
            column={column}
            onChange={(next) => setColumns(columns.map((c, i) => (i === index ? next : c)))}
          />
          <button type="button" className="link" onClick={() => setColumns(columns.filter((_, i) => i !== index))}>
            remove
          </button>
        </div>
      ))}
      <div className="row">
        <button type="button" onClick={() => setColumns([...columns, emptyColumn()])}>+ column</button>
        <span className="grow" />
        <button disabled={busy || !name} onClick={() => run(operations(), true)}>Preview SQL</button>
        <button disabled={busy || !name} onClick={create}>Create table</button>
      </div>
    </div>
  )
}

function ColumnInputs({ column, onChange }: { column: ColumnSpec; onChange: (c: ColumnSpec) => void }) {
  return (
    <>
      <input
        className="grow" placeholder="column name" value={column.name}
        onChange={(e) => onChange({ ...column, name: e.target.value })}
      />
      <select value={column.type} onChange={(e) => onChange({ ...column, type: e.target.value as ColumnType })}>
        {COLUMN_TYPES.map((type) => <option key={type}>{type}</option>)}
      </select>
      <label className="check">
        <input
          type="checkbox" checked={!column.nullable}
          onChange={(e) => onChange({ ...column, nullable: !e.target.checked })}
        />
        required
      </label>
      <label className="check">
        <input
          type="checkbox" checked={column.unique}
          onChange={(e) => onChange({ ...column, unique: e.target.checked })}
        />
        unique
      </label>
    </>
  )
}
