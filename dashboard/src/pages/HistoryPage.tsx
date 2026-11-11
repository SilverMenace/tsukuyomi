import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api.ts'

/** Every schema change ever applied to this project, newest first, with the SQL that ran. */
export default function HistoryPage({ projectId }: { projectId: string }) {
  const migrations = useQuery({ queryKey: ['migrations', projectId], queryFn: () => api.migrations(projectId) })

  if (migrations.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (migrations.isError) {
    return <p className="error">{errorMessage(migrations.error)}</p>
  }
  if (migrations.data.length === 0) {
    return <p className="muted">No migrations yet. They appear here when you change tables.</p>
  }

  return (
    <div className="stack">
      {migrations.data.map((m) => (
        <div className="card stack" key={m.version}>
          <div className="row">
            <strong>Version {m.version}</strong>
            <span className="muted">{new Date(m.appliedAt).toLocaleString()}</span>
          </div>
          <pre>{m.sql}</pre>
        </div>
      ))}
    </div>
  )
}
