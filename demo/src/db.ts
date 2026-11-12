import { createClient } from '../../sdk/src/index.ts'
import { ANON_KEY, SERVER_URL } from './config.ts'

// One client for the whole app: it holds the login session and the single
// live-updates connection, so sharing it keeps both consistent.
export const db = createClient(SERVER_URL, ANON_KEY)
