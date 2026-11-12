import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, errorMessage } from '../api.ts'
import type { Project } from '../api.ts'
import Secret from './Secret.tsx'

/** API URL, keys and a copy-paste example. */
export default function ApiPage({ project }: { project: Project }) {
  const queryClient = useQueryClient()
  const keys = useQuery({ queryKey: ['keys', project.id], queryFn: () => api.keys(project.id) })
  const schema = useQuery({ queryKey: ['schema', project.id], queryFn: () => api.schema(project.id) })
  const [fresh, setFresh] = useState<{ kind: string; key: string } | null>(null)

  const rotate = useMutation({
    mutationFn: (kind: string) => api.rotateKey(project.id, kind),
    onSuccess: (result) => {
      setFresh(result)
      return queryClient.invalidateQueries({ queryKey: ['keys', project.id] })
    },
  })

  const url = `${window.location.origin}/api/v1`
  const authUrl = `${window.location.origin}/auth/v1`
  const table = schema.data?.tables[0]?.name ?? 'your_table'

  return (
    <div className="stack">
      <div className="card stack">
        <h2>API URL</h2>
        <code>{url}</code>
      </div>

      <div className="card stack">
        <h2>Keys</h2>
        <p className="muted small">
          We store only a fingerprint of each key, so a key can be shown just once.
          If you lose one, make a new one; the old one stops working immediately.
        </p>
        {keys.isError && <p className="error">{errorMessage(keys.error)}</p>}
        {keys.data?.map((key) => (
          <div className="row" key={key.kind}>
            <strong>{key.kind}</strong>
            <code className="grow">{key.prefix}…</code>
            <button
              disabled={rotate.isPending}
              onClick={() => window.confirm(`Replace the ${key.kind} key? Apps using the old key will stop working.`) && rotate.mutate(key.kind)}
            >
              New key
            </button>
          </div>
        ))}
        {rotate.isError && <p className="error">{errorMessage(rotate.error)}</p>}
        {fresh && <Secret label={`new ${fresh.kind} key (copy it now)`} value={fresh.key} />}
      </div>

      <div className="card stack">
        <h2>End-user auth</h2>
        <p className="muted small">
          Apps built on this project sign their own users up here. These calls use the anon key
          (<code>apikey</code> header). A request that also sends the user's access token runs as
          that user, so the table's rules decide what it sees. Tokens last an hour; refresh to get
          a new pair (the old refresh token then stops working).
        </p>
        <pre>{`# sign up (login takes the same body and returns the same JSON)
curl -X POST "${authUrl}/signup" \\
  -H "apikey: <your anon key>" -H "Content-Type: application/json" \\
  -d '{"email": "ada@example.com", "password": "a long password"}'

# log in
curl -X POST "${authUrl}/login" \\
  -H "apikey: <your anon key>" -H "Content-Type: application/json" \\
  -d '{"email": "ada@example.com", "password": "a long password"}'

# read rows as that user
curl "${url}/${table}" \\
  -H "apikey: <your anon key>" -H "Authorization: Bearer <access_token>"

# get a new token pair
curl -X POST "${authUrl}/refresh" \\
  -H "apikey: <your anon key>" -H "Content-Type: application/json" \\
  -d '{"refresh_token": "<refresh_token>"}'`}</pre>
      </div>

      <div className="card stack">
        <h2>Try it</h2>
        <p className="muted small">With the service key, which bypasses all rules.</p>
        <pre>{`# list rows
curl "${url}/${table}?limit=20" -H "apikey: <your service key>"

# insert a row
curl -X POST "${url}/${table}" \\
  -H "apikey: <your service key>" -H "Content-Type: application/json" \\
  -d '{"column": "value"}'`}</pre>
      </div>
    </div>
  )
}
