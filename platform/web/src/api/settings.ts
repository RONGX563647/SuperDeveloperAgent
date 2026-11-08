import { apiFetch } from './http'

/** 部署服务器（设置页；secret 只写不读）。 */
export interface DeployServer {
  id: string
  name: string
  host: string
  port: number
  sshUser: string
  authType: 'KEY' | 'PASSWORD'
  keyPath: string | null
  remoteDir: string
  enabled: boolean
  isDefault: boolean
  hasSecret: boolean
  secretMasked: string | null
  lastTestAt: number | null
  lastTestOk: boolean | null
  lastTestMsg: string | null
  updatedBy: string | null
  updatedAt: number
}

export interface DeployServerInput {
  name?: string
  host: string
  port?: number
  sshUser: string
  authType?: 'KEY' | 'PASSWORD'
  keyPath?: string
  remoteDir?: string
  enabled?: boolean
  isDefault?: boolean
  /** 密码/口令；编辑时留空 = 不修改 */
  secret?: string
}

/** 模型连接（设置页；apiKey 只写不读）。 */
export interface ModelConnection {
  id: string
  name: string
  provider: string
  baseUrl: string
  modelName: string
  temperature: number
  maxIters: number
  enabled: boolean
  isDefault: boolean
  hasApiKey: boolean
  apiKeyMasked: string | null
  lastTestAt: number | null
  lastTestOk: boolean | null
  lastTestMsg: string | null
  lastTestLatencyMs: number | null
  updatedBy: string | null
  updatedAt: number
}

export interface ModelConnectionInput {
  name?: string
  provider?: string
  baseUrl: string
  modelName: string
  temperature?: number
  maxIters?: number
  enabled?: boolean
  isDefault?: boolean
  /** 留空 = 不修改 */
  apiKey?: string
}

export interface TestResult {
  ok: boolean
  latencyMs: number
  message: string
  /** 服务器测试：远端 docker 版本 */
  dockerVersion?: string | null
  /** 模型测试：模型回复首句 */
  reply?: string
}

export interface ModelOptions {
  activeId: string | null
  activeName: string | null
  modelName: string | null
  configured: boolean
}

// ---------------------------------------------------------------- 服务器

export function listServers(): Promise<DeployServer[]> {
  return apiFetch('/api/v1/settings/servers')
}

export function createServer(input: DeployServerInput): Promise<DeployServer> {
  return apiFetch('/api/v1/settings/servers', { method: 'POST', body: JSON.stringify(input) })
}

export function updateServer(id: string, input: DeployServerInput): Promise<DeployServer> {
  return apiFetch(`/api/v1/settings/servers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function deleteServer(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/servers/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function setDefaultServer(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/servers/${encodeURIComponent(id)}/default`, { method: 'POST' })
}

export function testServer(id: string): Promise<TestResult> {
  return apiFetch(`/api/v1/settings/servers/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export function testServerDraft(input: DeployServerInput): Promise<TestResult> {
  return apiFetch('/api/v1/settings/servers/test', {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

// ---------------------------------------------------------------- 模型

export function listModels(): Promise<ModelConnection[]> {
  return apiFetch('/api/v1/settings/models')
}

export function createModel(input: ModelConnectionInput): Promise<ModelConnection> {
  return apiFetch('/api/v1/settings/models', { method: 'POST', body: JSON.stringify(input) })
}

export function updateModel(id: string, input: ModelConnectionInput): Promise<ModelConnection> {
  return apiFetch(`/api/v1/settings/models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function deleteModel(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/models/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function setDefaultModel(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/models/${encodeURIComponent(id)}/default`, { method: 'POST' })
}

export function testModel(id: string): Promise<TestResult> {
  return apiFetch(`/api/v1/settings/models/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export function testModelDraft(input: {
  baseUrl: string
  modelName: string
  apiKey?: string
  connectionId?: string
}): Promise<TestResult> {
  return apiFetch('/api/v1/settings/models/test', {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

export function modelOptions(): Promise<ModelOptions> {
  return apiFetch('/api/v1/settings/models/options')
}
