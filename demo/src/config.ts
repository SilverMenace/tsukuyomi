// Which Tsukuyomi server and project this app talks to. These two lines are
// the whole "backend configuration" of the demo.
//
// For local development, put VITE_SERVER_URL and VITE_ANON_KEY in demo/.env.local
// (create a project with scripts/demo-project.mjs to get a key).
export const SERVER_URL: string = import.meta.env.VITE_SERVER_URL ?? 'https://tsukuyomi-l9kg.onrender.com'

// This anon key is meant to be public. What it may do is decided by permission
// rules stored as row-level security policies in the database, so shipping it
// in a web page is safe. The service key bypasses those rules and must never
// appear here.
export const ANON_KEY: string = import.meta.env.VITE_ANON_KEY ?? 'tsk_anon_88c8cec766a879a56d9f6dd7a23ff3f8b8624a2450f1ee64'
