import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api.ts'

/** The project's end users: the people who signed up through apps built on it (most recent 200). */
export default function UsersPage({ projectId }: { projectId: string }) {
  const users = useQuery({ queryKey: ['users', projectId], queryFn: () => api.endUsers(projectId) })

  if (users.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (users.isError) {
    return <p className="error">{errorMessage(users.error)}</p>
  }
  if (users.data.length === 0) {
    return <p className="muted">No end users yet. They appear here when someone signs up through your app.</p>
  }

  return (
    <div className="card scroll">
      <table>
        <thead>
          <tr><th>Email</th><th>Id</th><th>Created</th></tr>
        </thead>
        <tbody>
          {users.data.map((user) => (
            <tr key={user.id}>
              <td>{user.email}</td>
              <td><code>{user.id}</code></td>
              <td>{new Date(user.createdAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
