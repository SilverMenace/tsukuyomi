import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, errorMessage } from '../api.ts'
import type { RuleKind, RuleOperation, Table } from '../api.ts'

const OPERATIONS: { operation: RuleOperation; label: string }[] = [
  { operation: 'select', label: 'Read' },
  { operation: 'insert', label: 'Create' },
  { operation: 'update', label: 'Update' },
  { operation: 'delete', label: 'Delete' },
]

const KINDS: RuleKind[] = ['nobody', 'anyone', 'authenticated', 'owner']

/** Who may read, create, update and delete the rows of each table. */
export default function RulesPage({ projectId }: { projectId: string }) {
  const schema = useQuery({ queryKey: ['schema', projectId], queryFn: () => api.schema(projectId) })

  if (schema.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (schema.isError) {
    return <p className="error">{errorMessage(schema.error)}</p>
  }

  return (
    <div className="stack">
      <div className="stack muted small">
        <span><strong>nobody</strong>: only the service key can do it.</span>
        <span><strong>anyone</strong>: anyone with the anon key, logged in or not.</span>
        <span><strong>authenticated</strong>: any logged-in end user.</span>
        <span><strong>owner</strong>: only the logged-in user whose id is in the owner column of that row.</span>
        <span>Rules are enforced by the database (row-level security), not by the API code. The service key bypasses them.</span>
      </div>
      {schema.data.tables.map((table) => (
        <RulesCard key={table.name} projectId={projectId} table={table} />
      ))}
    </div>
  )
}

function RulesCard({ projectId, table }: { projectId: string; table: Table }) {
  const queryClient = useQueryClient()
  // Only a uuid column can hold a user id. For users the row's own id is the owner.
  const ownerColumns = table.columns.filter((c) => c.type === 'uuid' && (c.name !== 'id' || table.builtin))
  const hasOwnerColumn = table.columns.some((c) => c.type === 'uuid' && c.name !== 'id')

  const setRule = useMutation({
    mutationFn: (args: { operation: RuleOperation; rule: RuleKind; ownerColumn?: string }) =>
      api.migrate(projectId, [{ op: 'set_rule', table: table.name, ...args }], false),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ['schema', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['migrations', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
    ]),
  })

  return (
    <div className="card stack">
      <h2>{table.name}</h2>
      <table>
        <tbody>
          {OPERATIONS.map(({ operation, label }) => (
            <RuleRow
              key={operation} table={table} operation={operation} label={label}
              ownerColumns={ownerColumns.map((c) => c.name)} busy={setRule.isPending}
              onApply={(rule, ownerColumn) => setRule.mutate({ operation, rule, ownerColumn })}
            />
          ))}
        </tbody>
      </table>
      {setRule.isError && <p className="error">{errorMessage(setRule.error)}</p>}
      {table.builtin && <p className="muted small">Rows are created by signup.</p>}
      {!hasOwnerColumn && !table.builtin && (
        <p className="muted small">Add a uuid column that references users to use the owner rule.</p>
      )}
    </div>
  )
}

type RuleRowProps = {
  table: Table
  operation: RuleOperation
  label: string
  ownerColumns: string[]
  busy: boolean
  onApply: (rule: RuleKind, ownerColumn?: string) => void
}

function RuleRow({ table, operation, label, ownerColumns, busy, onApply }: RuleRowProps) {
  const stored = table.rules[operation]
  const storedKind: RuleKind = stored?.kind ?? 'nobody'
  const defaultOwner = () =>
    table.columns.find((c) => c.references === 'users')?.name ?? (table.builtin ? 'id' : '')
  const [kind, setKind] = useState<RuleKind>(storedKind)
  const [owner, setOwner] = useState(stored?.ownerColumn ?? defaultOwner())

  // Changing a select sends nothing; "Apply" is enabled only when the row differs from what is stored.
  const locked = table.builtin && (operation === 'insert' || operation === 'delete')
  const changed = kind !== storedKind || (kind === 'owner' && owner !== stored?.ownerColumn)
  const ready = kind !== 'owner' || owner !== ''

  return (
    <tr>
      <td>{label}</td>
      <td>
        <div className="row">
          <select value={kind} disabled={locked || busy} onChange={(e) => setKind(e.target.value as RuleKind)}>
            {KINDS.map((k) => <option key={k}>{k}</option>)}
          </select>
          {kind === 'owner' && (
            <select value={owner} disabled={locked || busy} onChange={(e) => setOwner(e.target.value)}>
              <option value="" disabled>(choose column)</option>
              {ownerColumns.map((name) => <option key={name}>{name}</option>)}
            </select>
          )}
        </div>
      </td>
      <td>
        <button
          disabled={locked || busy || !changed || !ready}
          onClick={() => onApply(kind, kind === 'owner' ? owner : undefined)}
        >
          Apply
        </button>
      </td>
    </tr>
  )
}
