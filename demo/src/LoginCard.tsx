import { useState } from 'react'
import type { FormEvent } from 'react'
import { db } from './db.ts'

export function LoginCard() {
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    // Server refusals come back as `error`, they are not thrown.
    const result =
      mode === 'login' ? await db.auth.signIn(email, password) : await db.auth.signUp(email, password)
    setBusy(false)
    if (result.error) setError(result.error.message)
    // On success App hears about it through db.auth.onChange, so nothing else to do here.
  }

  return (
    <div className="card">
      <h1>Tsukuyomi task board</h1>
      <p>
        This page has no backend code of its own. Anyone can sign up with any email; no
        verification email is sent, so use a made-up address such as name@example.com.
      </p>
      <form onSubmit={submit}>
        <input
          type="email"
          placeholder="Email"
          aria-label="Email"
          required
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <input
          type="password"
          placeholder="Password (at least 8 characters)"
          aria-label="Password"
          required
          minLength={8}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button className="primary" type="submit" disabled={busy}>
          {busy ? 'Working…' : mode === 'login' ? 'Log in' : 'Create account'}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
      <button
        className="link"
        type="button"
        onClick={() => {
          setMode(mode === 'login' ? 'signup' : 'login')
          setError(null)
        }}
      >
        {mode === 'login' ? 'Create account' : 'Log in'}
      </button>
      <p className="muted small">
        The free server sleeps when idle. The first request can take up to a minute.
      </p>
    </div>
  )
}
