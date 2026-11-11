import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { api, ApiError } from './api.ts'
import LoginPage from './pages/LoginPage.tsx'
import ProjectPage from './pages/ProjectPage.tsx'
import ProjectsPage from './pages/ProjectsPage.tsx'

export default function App() {
  const queryClient = useQueryClient()
  // "Who am I?" decides everything: a 401 means show the login page.
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })

  if (me.isPending) {
    return <p className="center muted">Loading… (the free server can take a minute to wake up)</p>
  }
  if (me.isError) {
    if (me.error instanceof ApiError && me.error.status === 401) {
      return <LoginPage />
    }
    return <p className="center error">Cannot reach the server: {me.error.message}</p>
  }

  async function logout() {
    await api.logout()
    queryClient.clear()
    await queryClient.invalidateQueries({ queryKey: ['me'] })
  }

  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">Tsukuyomi</Link>
        <span className="spacer" />
        <span className="muted">{me.data.email}</span>
        <button className="link" onClick={logout}>Log out</button>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<ProjectsPage />} />
          <Route path="/projects/:projectId/*" element={<ProjectPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  )
}
