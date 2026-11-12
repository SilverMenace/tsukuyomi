// Everything the dashboard knows about the backend lives in this file:
// the shapes of the JSON it exchanges and one function per endpoint.

export type Account = { id: string; email: string }

export type Project = {
  id: string
  name: string
  schemaName: string
  schemaVersion: number
  createdAt: string
}

export type CreatedProject = { project: Project; anonKey: string; serviceKey: string }

export type ColumnType =
  | 'text' | 'integer' | 'bigint' | 'numeric' | 'boolean'
  | 'timestamptz' | 'date' | 'uuid' | 'jsonb'

export const COLUMN_TYPES: ColumnType[] = [
  'text', 'integer', 'bigint', 'numeric', 'boolean', 'timestamptz', 'date', 'uuid', 'jsonb',
]

export type OnDelete = 'restrict' | 'cascade' | 'set_null'

export type Column = {
  name: string
  type: ColumnType
  nullable: boolean
  unique: boolean
  system: boolean
  references: string | null
  onDelete: OnDelete | null
}

// Who may do an operation on a table's rows. A missing operation means 'nobody'
// (only the service key), so the catalog only lists the three open kinds.
export type RuleOperation = 'select' | 'insert' | 'update' | 'delete'
export type RuleKind = 'nobody' | 'anyone' | 'authenticated' | 'owner'
export type Rule = { kind: Exclude<RuleKind, 'nobody'>; ownerColumn: string | null }

export type Table = {
  name: string
  columns: Column[]
  builtin: boolean // true for the platform-made users table
  rules: Partial<Record<RuleOperation, Rule>>
}
export type Catalog = { version: number; tables: Table[] }

export type ColumnSpec = {
  name: string
  type: ColumnType
  nullable: boolean
  unique: boolean
  references?: string // a foreign key to another table; the column must be uuid
  onDelete?: OnDelete
}

// The dashboard never sends SQL. It sends a list of these and the server
// generates the SQL.
export type Operation =
  | { op: 'create_table'; table: string; columns: ColumnSpec[] }
  | { op: 'drop_table'; table: string }
  | { op: 'add_column'; table: string; column: ColumnSpec }
  | { op: 'drop_column'; table: string; column: string }
  | { op: 'rename_table'; table: string; to: string }
  | { op: 'rename_column'; table: string; column: string; to: string }
  | { op: 'set_nullable'; table: string; column: string; nullable: boolean }
  | { op: 'set_unique'; table: string; column: string; unique: boolean }
  | { op: 'set_rule'; table: string; operation: RuleOperation; rule: RuleKind; ownerColumn?: string }

export type MigrationResult = { version: number; sql: string; applied: boolean }
export type MigrationRecord = { version: number; operations: string; sql: string; appliedAt: string }
export type ApiKeyInfo = { kind: 'anon' | 'service'; prefix: string; createdAt: string }
export type EndUser = { id: string; email: string; createdAt: string }
export type Row = Record<string, unknown>
export type RowFilter = { column: string; op: string; value: string }
export type RowsOptions = { limit: number; offset: number; order?: string; filter?: RowFilter }
export type RowsPage = { rows: Row[]; total: number | null }

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type Result<T> = { data: T; headers: Headers }

async function requestWithHeaders<T>(
  method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {},
): Promise<Result<T>> {
  const headers = body === undefined ? extraHeaders : { 'Content-Type': 'application/json', ...extraHeaders }
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    // The session cookie is HttpOnly, so this code never sees it. The browser
    // attaches it by itself because the request goes to our own origin.
    credentials: 'same-origin',
  })
  if (response.status === 204) {
    return { data: undefined as T, headers: response.headers }
  }
  const text = await response.text()
  const data = text ? JSON.parse(text) : undefined
  if (!response.ok) {
    throw new ApiError(
      response.status,
      data?.error?.code ?? 'error',
      data?.error?.message ?? `request failed (${response.status})`,
    )
  }
  return { data: data as T, headers: response.headers }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  return (await requestWithHeaders<T>(method, path, body)).data
}

const base = '/platform/v1'

export const api = {
  me: () => request<Account>('GET', `${base}/me`),
  signup: (email: string, password: string) => request<Account>('POST', `${base}/accounts`, { email, password }),
  login: (email: string, password: string) => request<void>('POST', `${base}/session`, { email, password }),
  logout: () => request<void>('DELETE', `${base}/session`),

  projects: () => request<Project[]>('GET', `${base}/projects`),
  project: (id: string) => request<Project>('GET', `${base}/projects/${id}`),
  createProject: (name: string) => request<CreatedProject>('POST', `${base}/projects`, { name }),
  deleteProject: (id: string) => request<void>('DELETE', `${base}/projects/${id}`),

  keys: (id: string) => request<ApiKeyInfo[]>('GET', `${base}/projects/${id}/keys`),
  rotateKey: (id: string, kind: string) =>
    request<{ kind: string; key: string }>('POST', `${base}/projects/${id}/keys/${kind}/rotate`),

  schema: (id: string) => request<Catalog>('GET', `${base}/projects/${id}/schema`),
  migrate: (id: string, operations: Operation[], dryRun: boolean) =>
    request<MigrationResult>('POST', `${base}/projects/${id}/migrations?dryRun=${dryRun}`, { operations }),
  migrations: (id: string) => request<MigrationRecord[]>('GET', `${base}/projects/${id}/migrations`),

  endUsers: (id: string) => request<EndUser[]>('GET', `${base}/projects/${id}/users`),

  rows: async (id: string, table: string, options: RowsOptions): Promise<RowsPage> => {
    const query = new URLSearchParams({ limit: String(options.limit), offset: String(options.offset) })
    if (options.order) {
      query.set('order', options.order)
    }
    if (options.filter) {
      query.set(options.filter.column, `${options.filter.op}.${options.filter.value}`)
    }
    // Prefer: count=exact makes the server also report how many rows match, ignoring limit
    const { data, headers } = await requestWithHeaders<Row[]>(
      'GET', `${base}/projects/${id}/tables/${table}/rows?${query}`, undefined, { Prefer: 'count=exact' },
    )
    const total = headers.get('X-Total-Count')
    return { rows: data, total: total === null ? null : Number(total) }
  },
  insertRow: (id: string, table: string, row: Row) =>
    request<Row>('POST', `${base}/projects/${id}/tables/${table}/rows`, row),
  updateRow: async (id: string, table: string, rowId: string, changes: Row) => {
    const rows = await request<Row[]>('PATCH', `${base}/projects/${id}/tables/${table}/rows?id=eq.${rowId}`, changes)
    return rows[0]
  },
  deleteRow: (id: string, table: string, rowId: string) =>
    request<Row[]>('DELETE', `${base}/projects/${id}/tables/${table}/rows?id=eq.${rowId}`),
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'something went wrong'
}
