export type Provider = 'openai' | 'openai-compatible'

export interface AgentConfig {
  agent_id: string
  name: string
  provider: string
  model_id: string
  base_url: string
  host?: string
  temperature: number
  max_tokens: number | null
  instructions: string
  markdown: boolean
  api_key?: string | null
  api_key_set?: boolean
}

export interface ProviderSpec {
  key: string
  label: string
  fields: string[]
  env: string
  available: boolean
  error: string
}

export function getProviders(): Promise<{ data: ProviderSpec[] }> {
  return request('/console/api/providers')
}

export interface AgentSummary {
  id: string
  name: string
  model: string
  provider: string
  temperature?: number | null
}

export interface ConsoleStatus {
  status: string
  os_name: string
  agno_version: string
  db: string
  auth_mode: string
  agent: AgentSummary
}

export interface SessionRow {
  session_id: string
  session_name?: string
  created_at?: string
  updated_at?: string
  total_tokens?: number
  user_id?: string
}

export interface RoleRow {
  slug: string
  name: string
  description?: string
  is_default: boolean
  scopes: { raw: string }[]
}

export interface UserRow {
  id: string
  email?: string
  name?: string
  status: string
  role_slug?: string
  role_name?: string
}

export interface Paginated<T> {
  data: T[]
  meta?: {
    page?: number
    limit?: number
    total?: number
    total_count?: number
    total_pages?: number
  }
}

let authToken = sessionStorage.getItem('agno_token') || ''
let currentUser: MeInfo | null = null

export interface MeInfo {
  user_id: string
  roles: string[]
  scopes: string[]
}

export function getAuthToken(): string {
  return authToken
}

export function getMe(): MeInfo | null {
  return currentUser
}

export async function bootstrapAuth(): Promise<string> {
  authToken = sessionStorage.getItem('agno_token') || ''
  if (!authToken) {
    currentUser = null
    return ''
  }
  try {
    currentUser = await request<MeInfo>('/console/api/me')
    return authToken
  } catch {
    authToken = ''
    currentUser = null
    sessionStorage.removeItem('agno_token')
    return ''
  }
}

export async function login(username: string, password: string): Promise<MeInfo> {
  const response = await fetch('/console/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!response.ok) {
    const text = await response.text()
    let message = text
    try {
      const parsed = JSON.parse(text)
      message = parsed.detail || text
    } catch {
      // 保持原文
    }
    throw new Error(message || '登录失败')
  }
  const data = await response.json()
  authToken = data.token
  sessionStorage.setItem('agno_token', authToken)
  currentUser = await request<MeInfo>('/console/api/me')
  return currentUser
}

export function logout(): void {
  authToken = ''
  currentUser = null
  sessionStorage.removeItem('agno_token')
}

export function createAccount(body: { username: string; user_id: string; password: string }): Promise<unknown> {
  return request('/console/api/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function cancelAgentRun(agentId: string, runId: string): Promise<unknown> {
  return request(`/os/agents/${encodeURIComponent(agentId)}/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST',
  })
}

export interface MetricsSummary {
  agent_runs_count: number
  agent_sessions_count: number
  team_runs_count: number
  team_sessions_count: number
  workflow_runs_count: number
  workflow_sessions_count: number
  users_count: number
  token_metrics: { input_tokens: number; output_tokens: number; total_tokens: number }
  model_metrics: { model_id: string; model_provider: string; count: number }[]
  date: string
  updated_at: string
}

export function getMetrics(): Promise<{ metrics: MetricsSummary[]; updated_at?: string }> {
  return request('/os/metrics')
}

export function getQueue(): Promise<any> {
  return request('/os/queue')
}

export function createMemory(body: { memory: string; user_id?: string; topics?: string[] }): Promise<unknown> {
  return request('/os/memories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteMemory(memoryId: string): Promise<unknown> {
  return request(`/os/memories/${encodeURIComponent(memoryId)}`, { method: 'DELETE' })
}

export function createLearning(body: {
  learning_type: string
  content: Record<string, any>
  namespace?: string
  user_id?: string
  agent_id?: string
}): Promise<unknown> {
  return request('/os/learnings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteLearning(learningId: string): Promise<unknown> {
  return request(`/os/learnings/${encodeURIComponent(learningId)}`, { method: 'DELETE' })
}

export function deleteApproval(approvalId: string): Promise<unknown> {
  return request(`/os/approvals/${encodeURIComponent(approvalId)}`, { method: 'DELETE' })
}

export function unassignRole(subject: string, role: string): Promise<unknown> {
  return request(`/os/authz/subjects/${encodeURIComponent(subject)}/roles/${encodeURIComponent(role)}`, {
    method: 'DELETE',
  })
}

export interface AuxModel {
  id: string
  name: string
  kind: 'embedding' | 'rerank' | 'tts' | 'stt'
  protocol: string
  base_url: string
  model: string
  extra: Record<string, any>
  enabled: boolean
  api_key_set?: boolean
  created_at?: string
}

export interface AuxProtocolOption {
  key: string
  label: string
}

export type AuxProtocols = Record<string, AuxProtocolOption[]>

export function listAuxModels(): Promise<{ data: AuxModel[]; protocols: AuxProtocols }> {
  return request('/console/api/aux-models')
}

export function createAuxModel(body: Record<string, any>): Promise<{ ok: boolean; id: string }> {
  return request('/console/api/aux-models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function updateAuxModel(id: string, body: Record<string, any>): Promise<unknown> {
  return request(`/console/api/aux-models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteAuxModel(id: string): Promise<unknown> {
  return request(`/console/api/aux-models/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testAuxModel(id: string): Promise<{ ok: boolean; status?: number; message: string }> {
  return request(`/console/api/aux-models/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export interface LibraryToolParam {
  name: string
  type: 'string' | 'integer' | 'number' | 'boolean'
  description: string
  required: boolean
}

export interface LibraryTool {
  id: string
  name: string
  label: string
  description: string
  method: string
  url: string
  headers: Record<string, string>
  body: string
  parameters: LibraryToolParam[]
  enabled: boolean
  created_at?: string
}

export function listLibraryTools(): Promise<{ data: LibraryTool[] }> {
  return request('/console/api/tool-library')
}

export function createLibraryTool(body: Record<string, any>): Promise<{ ok: boolean; id: string }> {
  return request('/console/api/tool-library', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function updateLibraryTool(id: string, body: Record<string, any>): Promise<unknown> {
  return request(`/console/api/tool-library/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteLibraryTool(id: string): Promise<unknown> {
  return request(`/console/api/tool-library/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testLibraryTool(
  id: string,
  args: Record<string, any>,
): Promise<{ ok: boolean; status?: number; output?: string; message?: string }> {
  return request(`/console/api/tool-library/${encodeURIComponent(id)}/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ arguments: args }),
  })
}

function withAuth(init?: HeadersInit): Headers {
  const headers = new Headers(init)
  if (authToken) {
    headers.set('Authorization', `Bearer ${authToken}`)
  }
  return headers
}

async function request<T>(url: string, init?: RequestInit, retry = true): Promise<T> {
  const response = await fetch(url, { ...init, headers: withAuth(init?.headers) })
  if (response.status === 401 && retry) {
    await bootstrapAuth()
    return request<T>(url, init, false)
  }
  if (!response.ok) {
    const text = await response.text()
    let message = text
    try {
      const parsed = JSON.parse(text)
      message = parsed.detail || parsed.message || text
    } catch {
      // 保持原始文本
    }
    throw new Error(message || `HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

export function getConfig(): Promise<AgentConfig> {
  return request<AgentConfig>('/console/api/config')
}

export function saveConfig(config: Partial<AgentConfig>): Promise<{ ok: boolean; agent: AgentSummary }> {
  return request('/console/api/config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
}

export function getStatus(): Promise<ConsoleStatus> {
  return request<ConsoleStatus>('/console/api/status')
}

export function listAgents(): Promise<{ id: string; name: string }[]> {
  return request('/os/agents')
}

export function listSessions(agentId: string, page = 1, limit = 20): Promise<Paginated<SessionRow>> {
  const params = new URLSearchParams({
    type: 'agent',
    component_id: agentId,
    page: String(page),
    limit: String(limit),
    sort_by: 'created_at',
    sort_order: 'desc',
  })
  return request<Paginated<SessionRow>>(`/os/sessions?${params.toString()}`)
}

export function getSession(sessionId: string): Promise<Record<string, any>> {
  const params = new URLSearchParams({ type: 'agent' })
  return request<Record<string, any>>(`/os/sessions/${encodeURIComponent(sessionId)}?${params.toString()}`)
}

export function listRoles(limit = 50): Promise<Paginated<RoleRow>> {
  return request<Paginated<RoleRow>>(`/os/authz/roles?page=1&limit=${limit}`)
}

export function createRole(body: { slug: string; name?: string; description?: string }): Promise<RoleRow> {
  return request('/os/authz/roles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteRole(slug: string): Promise<unknown> {
  return request(`/os/authz/roles/${encodeURIComponent(slug)}`, { method: 'DELETE' })
}

export function setRoleScopes(slug: string, scopes: string[]): Promise<unknown> {
  return request(`/os/authz/roles/${encodeURIComponent(slug)}/scopes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scopes }),
  })
}

export function listUsers(limit = 50): Promise<Paginated<UserRow>> {
  return request<Paginated<UserRow>>(`/os/users?page=1&limit=${limit}`)
}

export function assignRole(subject: string, role: string): Promise<unknown> {
  return request(`/os/authz/subjects/${encodeURIComponent(subject)}/roles`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
}

export function listAudit(limit = 30): Promise<Paginated<Record<string, any>>> {
  return request<Paginated<Record<string, any>>>(`/os/authz/audit?page=1&limit=${limit}`)
}

export interface TraceRow {
  trace_id: string
  name?: string
  status?: string
  duration?: number
  start_time?: string
  total_spans?: number
  error_count?: number
  run_id?: string
  session_id?: string
  agent_id?: string
}

export function listTraces(agentId: string, page = 1, limit = 20): Promise<Paginated<TraceRow>> {
  const params = new URLSearchParams({ agent_id: agentId, page: String(page), limit: String(limit) })
  return request<Paginated<TraceRow>>(`/os/traces?${params.toString()}`)
}

export function getTrace(traceId: string): Promise<Record<string, any>> {
  return request<Record<string, any>>(`/os/traces/${encodeURIComponent(traceId)}`)
}

export interface ServiceAccountRow {
  id: string
  name?: string
  principal?: string
  token_prefix?: string
  scopes?: string[]
  created_at?: number
  expires_at?: number | null
  last_used_at?: number | null
  revoked_at?: number | null
}

export function listServiceAccounts(): Promise<Paginated<ServiceAccountRow>> {
  return request<Paginated<ServiceAccountRow>>('/os/service-accounts?limit=100&include_revoked=true')
}

export function createServiceAccount(body: {
  name: string
  scopes?: { scope: string; effect?: 'allow' }[]
  expires_in_days?: number | null
  never_expires?: boolean
  allow_privileged_scopes?: boolean
}): Promise<ServiceAccountRow & { token: string }> {
  return request('/os/service-accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function revokeServiceAccount(id: string): Promise<unknown> {
  return request(`/os/service-accounts/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export interface ScheduleRow {
  id: string
  name?: string
  description?: string
  method?: string
  endpoint?: string
  cron_expr?: string
  timezone?: string
  enabled?: boolean
  next_run_at?: string | number | null
  last_run_at?: string | number | null
  created_at?: string | number
}

export function listSchedules(): Promise<Paginated<ScheduleRow>> {
  return request<Paginated<ScheduleRow>>('/os/schedules?limit=100')
}

export function createSchedule(body: {
  name: string
  cron_expr: string
  endpoint: string
  method?: string
  description?: string
  timezone?: string
}): Promise<ScheduleRow> {
  return request('/os/schedules', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteSchedule(id: string): Promise<unknown> {
  return request(`/os/schedules/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export interface ColumnDef {
  key: string
  label: string
  width?: number
  type?: 'time' | 'tag' | 'mono'
}

export function listKnowledge(page = 1, limit = 20): Promise<Paginated<any>> {
  return request<Paginated<any>>(`/os/knowledge/content?page=${page}&limit=${limit}`)
}

export function listMemories(page = 1, limit = 20): Promise<Paginated<any>> {
  return request<Paginated<any>>(`/os/memories?page=${page}&limit=${limit}`)
}

export function listLearnings(page = 1, limit = 20): Promise<Paginated<any>> {
  return request<Paginated<any>>(`/os/learnings?page=${page}&limit=${limit}`)
}

export function listApprovals(page = 1, limit = 20): Promise<Paginated<any>> {
  return request<Paginated<any>>(`/os/approvals?page=${page}&limit=${limit}`)
}

export function listEvalRuns(page = 1, limit = 20): Promise<Paginated<any>> {
  return request<Paginated<any>>(`/os/eval-runs?page=${page}&limit=${limit}`)
}

export async function listTeams(): Promise<Paginated<any>> {
  const data = await request<any[]>('/os/teams')
  return { data: data || [] }
}

export async function listWorkflows(): Promise<Paginated<any>> {
  const data = await request<any[]>('/os/workflows')
  return { data: data || [] }
}

export function listFilesystemEntries(agentId: string, directory: string): Promise<any> {
  const params = new URLSearchParams({ agent_id: agentId, directory, limit: '50', page: '1' })
  return request<any>(`/os/filesystem/entries?${params.toString()}`)
}

export function renameSession(sessionId: string, name: string): Promise<unknown> {
  const params = new URLSearchParams({ type: 'agent' })
  return request(`/os/sessions/${encodeURIComponent(sessionId)}/rename?${params.toString()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ session_name: name }),
  })
}

export function deleteSession(sessionId: string): Promise<unknown> {
  const params = new URLSearchParams({ type: 'agent' })
  return request(`/os/sessions/${encodeURIComponent(sessionId)}?${params.toString()}`, { method: 'DELETE' })
}

export function forkSession(agentId: string, sessionId: string): Promise<unknown> {
  return request(
    `/os/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/fork`,
    { method: 'POST' },
  )
}

export function compactSession(agentId: string, sessionId: string): Promise<unknown> {
  return request(
    `/os/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/compact`,
    { method: 'POST' },
  )
}

export function listSessionRuns(sessionId: string, limit = 20): Promise<Paginated<any>> {
  const params = new URLSearchParams({ limit: String(limit), page: '1' })
  return request<Paginated<any>>(`/os/sessions/${encodeURIComponent(sessionId)}/runs?${params.toString()}`)
}

export function createUser(body: { id: string; email?: string; name?: string }): Promise<UserRow> {
  return request('/os/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function updateUser(
  userId: string,
  body: { email?: string; name?: string; disabled?: boolean },
): Promise<UserRow> {
  return request(`/os/users/${encodeURIComponent(userId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteUser(userId: string): Promise<unknown> {
  return request(`/os/users/${encodeURIComponent(userId)}`, { method: 'DELETE' })
}

export function enableSchedule(id: string): Promise<unknown> {
  return request(`/os/schedules/${encodeURIComponent(id)}/enable`, { method: 'POST' })
}

export function disableSchedule(id: string): Promise<unknown> {
  return request(`/os/schedules/${encodeURIComponent(id)}/disable`, { method: 'POST' })
}

export function triggerSchedule(id: string): Promise<unknown> {
  return request(`/os/schedules/${encodeURIComponent(id)}/trigger`, { method: 'POST' })
}

export function resolveApproval(
  id: string,
  status: 'approved' | 'rejected',
  resolvedBy?: string,
): Promise<unknown> {
  return request(`/os/approvals/${encodeURIComponent(id)}/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, resolved_by: resolvedBy }),
  })
}

export interface ConsoleComponents {
  agents: AgentSummary[]
  teams: { id: string; name?: string; description?: string; member_ids: string[] }[]
  workflows: { id: string; name?: string; description?: string; step_count: number }[]
}

export function getComponents(): Promise<ConsoleComponents> {
  return request<ConsoleComponents>('/console/api/components')
}

export function createComponent(body: {
  kind: 'agent' | 'team' | 'workflow'
  id: string
  name?: string
  description?: string
  provider?: string
  model_id?: string
  base_url?: string
  host?: string
  api_key?: string
  temperature?: number
  instructions?: string
  member_ids?: string[]
  step_ids?: string[]
  aux_bindings?: { id: string; intro: string }[]
  tool_ids?: string[]
}): Promise<unknown> {
  return request('/console/api/components', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteComponent(kind: 'agent' | 'team' | 'workflow', id: string): Promise<unknown> {
  return request(`/console/api/components/${kind}/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function getComponentDefinition(kind: 'agent' | 'team' | 'workflow', id: string): Promise<Record<string, any>> {
  return request<Record<string, any>>(`/console/api/components/${kind}/${encodeURIComponent(id)}`)
}

export function updateComponent(
  kind: 'agent' | 'team' | 'workflow',
  id: string,
  body: Record<string, any>,
): Promise<unknown> {
  return request(`/console/api/components/${kind}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export interface ComponentVersion {
  version: number
  created_at: string
  definition: Record<string, any>
}

export function getComponentVersions(
  kind: 'agent' | 'team' | 'workflow',
  id: string,
): Promise<{ current?: number; versions: ComponentVersion[] }> {
  return request(`/console/api/components/${kind}/${encodeURIComponent(id)}/versions`)
}

export function publishComponentVersion(
  kind: 'agent' | 'team' | 'workflow',
  id: string,
  version: number,
): Promise<unknown> {
  return request(`/console/api/components/${kind}/${encodeURIComponent(id)}/versions/${version}/publish`, {
    method: 'POST',
  })
}

interface StreamRunOptions {
  agentId: string
  message: string
  sessionId: string
  userId?: string
  signal?: AbortSignal
  onEvent: (event: string, data: Record<string, any>) => void
}

async function openRunStream(options: StreamRunOptions, retry = true): Promise<Response> {
  const form = new FormData()
  form.append('message', options.message)
  form.append('stream', 'true')
  form.append('session_id', options.sessionId)
  if (options.userId) {
    form.append('user_id', options.userId)
  }

  const response = await fetch(`/os/agents/${encodeURIComponent(options.agentId)}/runs`, {
    method: 'POST',
    body: form,
    signal: options.signal,
    headers: withAuth(),
  })

  if (response.status === 401 && retry) {
    await bootstrapAuth()
    return openRunStream(options, false)
  }
  return response
}

export async function streamRun(options: StreamRunOptions): Promise<void> {
  const response = await openRunStream(options)

  if (!response.ok) {
    throw new Error((await response.text()) || `HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const emit = (block: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
    if (!dataLines.length) {
      return
    }
    try {
      options.onEvent(event, JSON.parse(dataLines.join('\n')))
    } catch {
      options.onEvent(event, { content: dataLines.join('\n') })
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let match: RegExpExecArray | null
    const separator = /\r?\n\r?\n/g
    let lastIndex = 0
    while ((match = separator.exec(buffer)) !== null) {
      emit(buffer.slice(lastIndex, match.index))
      lastIndex = match.index + match[0].length
    }
    buffer = buffer.slice(lastIndex)
  }

  if (buffer.trim()) {
    emit(buffer)
  }
}
