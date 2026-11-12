import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, COLUMN_TYPES, errorMessage } from '../api.ts'
import type { Column, ColumnSpec, ColumnType, OnDelete, Operation, Table } from '../api.ts'

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

  const tableNames = schema.data.tables.map((t) => t.name)

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
        <TableCard
          key={table.name} table={table} tables={tableNames} run={run} busy={migrate.isPending}
        />
      ))}

      <NewTableForm tables={tableNames} run={run} busy={migrate.isPending} />
      <Relations tables={schema.data.tables} />
    </div>
  )
}

type Run = (operations: Operation[], dryRun?: boolean) => void

// The backend only accepts names like this; checking here just saves a round trip.
const NAME_PATTERN = /^[a-z][a-z0-9_]{0,27}$/

/** Read-only list of foreign keys: which column points at which table. */
function Relations({ tables }: { tables: Table[] }) {
  const relations = tables.flatMap((t) =>
    t.columns
      .filter((c) => c.references)
      .map((c) => `${t.name}.${c.name} → ${c.references}.id (on delete ${(c.onDelete ?? 'restrict').replace('_', ' ')})`),
  )
  return (
    <div className="card stack">
      <h2>Relations</h2>
      {relations.length === 0 && <p className="muted">No relations yet</p>}
      {relations.map((line) => <div key={line}>{line}</div>)}
    </div>
  )
}

/** Inline form that renames a table. The built-in users table never shows it. */
function RenameTable({ table, run, busy, onDone }: { table: string; run: Run; busy: boolean; onDone: () => void }) {
  const [name, setName] = useState(table)
  const valid = NAME_PATTERN.test(name) && name !== table

  function submit(dryRun: boolean) {
    run([{ op: 'rename_table', table, to: name }], dryRun)
    if (!dryRun) {
      onDone()
    }
  }

  return (
    <div className="row">
      <input placeholder="new table name" value={name} onChange={(e) => setName(e.target.value)} />
      <button disabled={busy || !valid} onClick={() => submit(true)}>Preview SQL</button>
      <button disabled={busy || !valid} onClick={() => submit(false)}>Save table name</button>
      <button className="link" onClick={onDone}>cancel</button>
    </div>
  )
}

/**
 * Inline editor for one existing column. It sends only what changed, in the
 * order set_nullable, set_unique, rename_column, so each step still finds the
 * column under its old name.
 */
function ColumnEditor({ table, column, run, busy, onDone }: {
  table: Table; column: Column; run: Run; busy: boolean; onDone: () => void
}) {
  const [name, setName] = useState(column.name)
  const [required, setRequired] = useState(!column.nullable)
  const [unique, setUnique] = useState(column.unique)

  const operations = (): Operation[] => {
    const ops: Operation[] = []
    if (required !== !column.nullable) {
      ops.push({ op: 'set_nullable', table: table.name, column: column.name, nullable: !required })
    }
    if (unique !== column.unique) {
      ops.push({ op: 'set_unique', table: table.name, column: column.name, unique })
    }
    if (name !== column.name) {
      ops.push({ op: 'rename_column', table: table.name, column: column.name, to: name })
    }
    return ops
  }
  const ready = NAME_PATTERN.test(name) && operations().length > 0

  function submit(dryRun: boolean) {
    run(operations(), dryRun)
    if (!dryRun) {
      onDone()
    }
  }

  return (
    <div className="row">
      <input className="grow" placeholder="column name" value={name} onChange={(e) => setName(e.target.value)} />
      <label className="check">
        {/* columns the owner adds to the built-in users table must stay optional */}
        <input type="checkbox" checked={required} disabled={table.builtin} onChange={(e) => setRequired(e.target.checked)} />
        required
      </label>
      <label className="check">
        {/* a jsonb value cannot be compared for equality, so it cannot be unique */}
        <input type="checkbox" checked={unique} disabled={column.type === 'jsonb'} onChange={(e) => setUnique(e.target.checked)} />
        unique
      </label>
      <button disabled={busy || !ready} onClick={() => submit(true)}>Preview SQL</button>
      <button disabled={busy || !ready} onClick={() => submit(false)}>Save column</button>
      <button className="link" onClick={onDone}>cancel</button>
    </div>
  )
}

function TableCard({ table, tables, run, busy }: { table: Table; tables: string[]; run: Run; busy: boolean }) {
  const { projectId = '' } = useParams()
  const [column, setColumn] = useState<ColumnSpec>(emptyColumn())
  const [renaming, setRenaming] = useState(false) // is the table-name editor open?
  const [editing, setEditing] = useState('') // name of the column whose editor is open

  function addColumn(dryRun: boolean) {
    run([{ op: 'add_column', table: table.name, column }], dryRun)
    if (!dryRun) {
      setColumn(emptyColumn())
    }
  }

  return (
    <div className="card stack">
      <div className="row">
        <h2>{table.name}</h2>
        {table.builtin && <span className="muted">(built in)</span>}
        {!table.builtin && !renaming && (
          <button className="link" disabled={busy} onClick={() => setRenaming(true)}>rename</button>
        )}
        <span className="grow" />
        <Link to={`/projects/${projectId}/data/${table.name}`}>Browse rows</Link>
        {!table.builtin && (
          <button
            className="danger" disabled={busy}
            onClick={() => window.confirm(`Delete table "${table.name}" and all its rows?`) && run([{ op: 'drop_table', table: table.name }])}
          >
            Delete table
          </button>
        )}
      </div>
      {renaming && (
        <RenameTable table={table.name} run={run} busy={busy} onDone={() => setRenaming(false)} />
      )}
      <table>
        <thead>
          <tr><th>Column</th><th>Type</th><th>Required</th><th>Unique</th><th /></tr>
        </thead>
        <tbody>
          {table.columns.map((c) => (
            <Fragment key={c.name}>
              <tr>
                <td>{c.name} {c.system && <span className="muted small">(automatic)</span>}</td>
                <td>
                  {c.type}
                  {c.references && <span className="muted"> → {c.references} ({(c.onDelete ?? 'restrict').replace('_', ' ')})</span>}
                </td>
                <td>{c.nullable ? '' : 'yes'}</td>
                <td>{c.unique ? 'yes' : ''}</td>
                <td>
                  {!c.system && (
                    <>
                      <button className="link" disabled={busy} onClick={() => setEditing(c.name)}>edit</button>
                      <button
                        className="link danger" disabled={busy}
                        onClick={() => window.confirm(`Delete column "${c.name}" and its data?`) && run([{ op: 'drop_column', table: table.name, column: c.name }])}
                      >
                        remove
                      </button>
                    </>
                  )}
                </td>
              </tr>
              {editing === c.name && (
                <tr>
                  <td colSpan={5}>
                    <ColumnEditor table={table} column={c} run={run} busy={busy} onDone={() => setEditing('')} />
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
      <div className="row">
        <ColumnInputs column={column} tables={tables} onChange={setColumn} forceOptional={table.builtin} />
        <button disabled={busy || !column.name} onClick={() => addColumn(true)}>Preview SQL</button>
        <button disabled={busy || !column.name} onClick={() => addColumn(false)}>Add column</button>
      </div>
    </div>
  )
}

function NewTableForm({ tables, run, busy }: { tables: string[]; run: Run; busy: boolean }) {
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
            tables={tables.filter((t) => t !== name)} // the new table does not exist yet, so it cannot be referenced
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

const ON_DELETE_LABELS: Record<OnDelete, string> = { restrict: 'restrict', cascade: 'cascade', set_null: 'set null' }

type ColumnInputsProps = {
  column: ColumnSpec
  tables: string[]
  onChange: (c: ColumnSpec) => void
  forceOptional?: boolean // columns added to the built-in users table must be nullable
}

function ColumnInputs({ column, tables, onChange, forceOptional = false }: ColumnInputsProps) {
  // A foreign key must be a uuid (it points at the other table's id), so choosing one forces the type
  function setReference(table: string) {
    if (table === '') {
      const { references: _references, onDelete: _onDelete, ...rest } = column
      onChange(rest)
    } else {
      onChange({ ...column, type: 'uuid', references: table, onDelete: column.onDelete ?? 'restrict' })
    }
  }

  return (
    <>
      <input
        className="grow" placeholder="column name" value={column.name}
        onChange={(e) => onChange({ ...column, name: e.target.value })}
      />
      <select
        value={column.type} disabled={column.references !== undefined}
        onChange={(e) => onChange({ ...column, type: e.target.value as ColumnType })}
      >
        {COLUMN_TYPES.map((type) => <option key={type}>{type}</option>)}
      </select>
      <select value={column.references ?? ''} onChange={(e) => setReference(e.target.value)}>
        <option value="">(no reference)</option>
        {tables.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      {column.references !== undefined && (
        <select
          value={column.onDelete ?? 'restrict'}
          onChange={(e) => onChange({ ...column, onDelete: e.target.value as OnDelete })}
        >
          {(Object.keys(ON_DELETE_LABELS) as OnDelete[]).map((o) => (
            <option key={o} value={o}>on delete: {ON_DELETE_LABELS[o]}</option>
          ))}
        </select>
      )}
      <label className="check">
        <input
          type="checkbox" checked={!column.nullable && !forceOptional} disabled={forceOptional}
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
