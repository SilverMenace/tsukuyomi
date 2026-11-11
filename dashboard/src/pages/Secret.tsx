import { useState } from 'react'

/** A secret value with a copy button. */
export default function Secret({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    await navigator.clipboard.writeText(value)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <div>
      <div className="muted small">{label}</div>
      <div className="row">
        <code className="grow">{value}</code>
        <button type="button" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
      </div>
    </div>
  )
}
