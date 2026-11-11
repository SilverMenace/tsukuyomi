import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, errorMessage } from '../api.ts'

export default function LoginPage() {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (mode === 'signup') {
        await api.signup(email, password)
      } else {
        await api.login(email, password)
      }
      // The server set the session cookie; ask "who am I?" again.
      await queryClient.invalidateQueries({ queryKey: ['me'] })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth">
      <h1>Tsukuyomi</h1>
      <p className="muted">Design tables, get an API.</p>
      <form onSubmit={submit} className="card stack">
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </label>
        <label>
          Password
          <input
            type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            required minLength={8} maxLength={72}
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button disabled={busy}>{mode === 'signup' ? 'Create account' : 'Log in'}</button>
        <button type="button" className="link" onClick={() => setMode(mode === 'signup' ? 'login' : 'signup')}>
          {mode === 'signup' ? 'I already have an account' : 'Create an account'}
        </button>
      </form>
    </div>
  )
}
