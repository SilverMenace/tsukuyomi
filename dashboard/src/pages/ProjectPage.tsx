import { useQuery } from '@tanstack/react-query'
import { Link, NavLink, Navigate, Route, Routes, useParams } from 'react-router-dom'
import { api, errorMessage } from '../api.ts'
import ApiPage from './ApiPage.tsx'
import DataPage from './DataPage.tsx'
import HistoryPage from './HistoryPage.tsx'
import RulesPage from './RulesPage.tsx'
import TablesPage from './TablesPage.tsx'
import UsersPage from './UsersPage.tsx'

/** Shell for one project: its name and the tabs. Each tab is its own page file. */
export default function ProjectPage() {
  const { projectId = '' } = useParams()
  const project = useQuery({ queryKey: ['project', projectId], queryFn: () => api.project(projectId) })

  if (project.isPending) {
    return <p className="muted">Loading…</p>
  }
  if (project.isError) {
    return <p className="error">{errorMessage(project.error)} <Link to="/">Back to projects</Link></p>
  }

  // Absolute paths on purpose: inside a "/*" route, React Router resolves a
  // relative link against the current tab's URL, so "rules" would become
  // ".../tables/rules".
  const base = `/projects/${projectId}`

  return (
    <div className="stack">
      <h1>{project.data.name}</h1>
      <nav className="tabs">
        <NavLink to={`${base}/tables`}>Tables</NavLink>
        <NavLink to={`${base}/data`}>Data</NavLink>
        <NavLink to={`${base}/rules`}>Rules</NavLink>
        <NavLink to={`${base}/users`}>Users</NavLink>
        <NavLink to={`${base}/api`}>API</NavLink>
        <NavLink to={`${base}/history`}>Migrations</NavLink>
      </nav>
      <Routes>
        <Route path="tables" element={<TablesPage projectId={projectId} />} />
        <Route path="data" element={<DataPage projectId={projectId} />} />
        <Route path="data/:table" element={<DataPage projectId={projectId} />} />
        <Route path="rules" element={<RulesPage projectId={projectId} />} />
        <Route path="users" element={<UsersPage projectId={projectId} />} />
        <Route path="api" element={<ApiPage project={project.data} />} />
        <Route path="history" element={<HistoryPage projectId={projectId} />} />
        <Route path="*" element={<Navigate to={`${base}/tables`} replace />} />
      </Routes>
    </div>
  )
}
