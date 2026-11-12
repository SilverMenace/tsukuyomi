import { useEffect, useState } from 'react'
import type { User } from '../../sdk/src/index.ts'
import { SERVER_URL } from './config.ts'
import { db } from './db.ts'
import { LoginCard } from './LoginCard.tsx'
import { TaskList } from './TaskList.tsx'

const NAME_KEY = 'demo.displayName'

// A made-up name, so we never use the email as a public label.
function loadDisplayName(): string {
  try {
    const saved = localStorage.getItem(NAME_KEY)
    if (saved) return saved
  } catch {
    // localStorage can be blocked; fall through to a fresh name.
  }
  return `guest-${Math.floor(1000 + Math.random() * 9000)}`
}

export default function App() {
  // The SDK restores a saved session on load, so start from it.
  const [user, setUser] = useState<User | null>(db.auth.user)
  const [displayName, setDisplayName] = useState(loadDisplayName)

  // onChange fires on login and logout; it returns the unsubscribe function.
  useEffect(() => db.auth.onChange(setUser), [])

  function changeName(name: string) {
    setDisplayName(name)
    try {
      localStorage.setItem(NAME_KEY, name)
    } catch {
      // Not saving is fine; the name still works for this visit.
    }
  }

  return (
    <div className="page">
      {user ? (
        <>
          <header className="header">
            <h1>Tsukuyomi task board</h1>
            <div className="who">
              <span className="muted">{user.email}</span>
              <input
                type="text"
                aria-label="Display name"
                placeholder="Display name"
                value={displayName}
                maxLength={40}
                onChange={(e) => changeName(e.target.value)}
              />
              <button className="link" onClick={() => void db.auth.signOut()}>
                Log out
              </button>
            </div>
          </header>
          <p className="muted status">
            Live: changes appear without reloading. Open this page in a second window, log in as
            someone else, and watch the Team board.
          </p>
          {/* key = user id: a different user remounts the boards, so no old rows survive. */}
          <main className="columns" key={user.id}>
            <TaskList
              table="private_tasks"
              title="My tasks"
              subtitle="Private. Only you can see these; the database refuses everyone else."
              showAuthors={false}
              displayName={displayName}
            />
            <TaskList
              table="shared_tasks"
              title="Team board"
              subtitle="Shared. Everyone who is logged in can see and change everything here, as it happens."
              showAuthors
              displayName={displayName}
            />
          </main>
        </>
      ) : (
        <LoginCard />
      )}
      <footer className="footer muted">
        Built on Tsukuyomi: a self-hosted backend. This page is static files only.{' '}
        <a href="https://github.com/SilverMenace/tsukuyomi">Source</a>{' '}
        <a href={SERVER_URL}>Dashboard</a>
      </footer>
    </div>
  )
}
