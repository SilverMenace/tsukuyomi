import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api.ts'
import type { CreatedProject } from '../api.ts'
import Secret from './Secret.tsx'

export default function ProjectsPage() {
  const queryClient = useQueryClient()
  const projects = useQuery({ queryKey: ['projects'], queryFn: api.projects })
  const [name, setName] = useState('')
  // The keys of a new project are shown once, here, and never again.
  const [created, setCreated] = useState<CreatedProject | null>(null)

  const create = useMutation({
    mutationFn: (projectName: string) => api.createProject(projectName),
    onSuccess: (result) => {
      setCreated(result)
      setName('')
      return queryClient.invalidateQueries({ queryKey: ['projects'] })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate(name)
  }

  return (
    <div className="stack">
      <h1>Projects</h1>

      {created && (
        <div className="card stack notice">
          <strong>Project “{created.project.name}” created. Copy these keys now; they are not shown again.</strong>
          <Secret label="anon key (safe to use in a browser)" value={created.anonKey} />
          <Secret label="service key (secret, full access)" value={created.serviceKey} />
          <div>
            <Link to={`/projects/${created.project.id}`} className="button">Open project</Link>
          </div>
        </div>
      )}

      {projects.isPending && <p className="muted">Loading…</p>}
      {projects.isError && <p className="error">{errorMessage(projects.error)}</p>}
      {projects.data?.length === 0 && <p className="muted">No projects yet. Create your first one below.</p>}
      <ul className="list">
        {projects.data?.map((project) => (
          <li key={project.id}>
            <Link to={`/projects/${project.id}`}>{project.name}</Link>
            <span className="muted"> schema v{project.schemaVersion}</span>
          </li>
        ))}
      </ul>

      <form onSubmit={submit} className="card row">
        <input
          placeholder="New project name" value={name} onChange={(e) => setName(e.target.value)}
          required maxLength={50}
        />
        <button disabled={create.isPending}>Create project</button>
      </form>
      {create.isError && <p className="error">{errorMessage(create.error)}</p>}
    </div>
  )
}
