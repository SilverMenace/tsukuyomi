import { useEffect, useRef, useState } from 'react'

type Line = { at: string; text: string }

/**
 * Watches one table over the live-updates WebSocket and lists what arrives.
 * It talks to /realtime/v1 exactly as an app would: with an API key, not with
 * the dashboard's login. Paste a key, press Watch, then change a row.
 */
export default function LiveTester({ tables }: { tables: string[] }) {
  const [key, setKey] = useState('')
  const [table, setTable] = useState(tables[0] ?? '')
  const [status, setStatus] = useState<'off' | 'connecting' | 'watching'>('off')
  const [lines, setLines] = useState<Line[]>([])
  const socket = useRef<WebSocket | null>(null)

  // Close the connection when the page is left.
  useEffect(() => () => socket.current?.close(), [])

  function log(text: string) {
    setLines((old) => [{ at: new Date().toLocaleTimeString(), text }, ...old].slice(0, 50))
  }

  function watch() {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${scheme}://${window.location.host}/realtime/v1`)
    socket.current = ws
    setStatus('connecting')
    ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', apikey: key.trim() }))
    ws.onmessage = (message) => {
      const msg = JSON.parse(message.data)
      if (msg.type === 'ready') {
        ws.send(JSON.stringify({ type: 'subscribe', table }))
      } else if (msg.type === 'subscribed') {
        setStatus('watching')
        log(`watching ${msg.table} as ${key.trim().startsWith('tsk_service_') ? 'service' : 'anon'}`)
      } else if (msg.type === 'event') {
        log(`${msg.op} ${msg.id}${msg.row ? ' ' + JSON.stringify(msg.row) : ''}`)
      } else if (msg.type === 'resync') {
        log('server says: reload the table')
      } else if (msg.type === 'error') {
        log(`error ${msg.code}: ${msg.message}`)
      }
    }
    ws.onclose = () => {
      setStatus('off')
      socket.current = null
    }
  }

  return (
    <div className="stack">
      <div className="row">
        <input
          className="grow"
          type="password"
          placeholder="Paste an anon or service key"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          disabled={status !== 'off'}
        />
        <select value={table} onChange={(e) => setTable(e.target.value)} disabled={status !== 'off'}>
          {tables.map((name) => (
            <option key={name}>{name}</option>
          ))}
        </select>
        {status === 'off' ? (
          <button disabled={!key.trim() || !table} onClick={watch}>Watch</button>
        ) : (
          <button onClick={() => socket.current?.close()}>{status === 'connecting' ? 'Connecting…' : 'Stop'}</button>
        )}
      </div>
      {lines.length > 0 && (
        <pre>{lines.map((line) => `${line.at}  ${line.text}`).join('\n')}</pre>
      )}
    </div>
  )
}
