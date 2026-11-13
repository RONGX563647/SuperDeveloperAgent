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
  /** 编辑态草稿测试用：带上已保存的 id 才能沿用原密钥并把结果写回列表状态 */
  id?: string
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

// ---------------------------------------------------------------- 项目环境变量

/** 项目环境变量（部署时注入容器；敏感项只返回掩码）。 */
export interface ProjectEnvVar {
  projectId: string
  env: string
  key: string
  secret: boolean
  value: string
  updatedBy: string | null
  updatedAt: number
}

export function listProjectEnvVars(projectId: string, env: string): Promise<ProjectEnvVar[]> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/envs?${q}`)
}

/** 新增/修改；value 留空表示保持原值（新建时必填）。 */
export function saveProjectEnvVar(
  projectId: string,
  input: { env: string, key: string, value?: string, secret?: boolean }
): Promise<ProjectEnvVar> {
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/envs`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function deleteProjectEnvVar(projectId: string, env: string, key: string): Promise<void> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/envs/${encodeURIComponent(key)}?${q}`,
    { method: 'DELETE' }
  )
}

// ---------------------------------------------------------------- 环境模板（dev/test/prod）

export type RuntimeMode = 'LOCAL_DEV' | 'LOCAL_TEST' | 'SERVER'

export interface EnvTemplate {
  env: string
  network: string | null
  defaultTarget: string
  defaultPort: number
  healthPath: string | null
  /** 运行形态：dev=本机进程+本机容器，test=本机容器，prod=服务器容器 */
  runtimeMode: RuntimeMode
  /** 资源命名空间（agent-dev / agent-test / agent-prod） */
  namespace: string
  /** SERVER 形态绑定的服务器 id */
  serverId: string | null
  updatedAt: number
}

export interface EnvTemplateRow {
  template: EnvTemplate
  resourceCount: number
  /** 运行中容器数（未启动不算异常） */
  runningCount: number
  /** 已停止容器数（部署时会自动启动） */
  stoppedCount: number
  /** 缺连接信息（主机/端口/用户名/密码）的中间件数，只有这类才算异常 */
  missingCount: number
  varCount: number
  secretCount: number
}

/** 中间件资源（密码只返回掩码）。 */
export interface EnvResourceView {
  id: string
  env: string
  kind: string
  name: string
  host: string
  port: number
  /** 容器内端口（dev 用发布端口 / test 用容器内端口） */
  containerPort: number
  /** 共享网络内的地址（容器名） */
  internalHost: string | null
  /** 当前环境形态（寻址依据） */
  runtimeMode: RuntimeMode
  databaseName: string | null
  username: string | null
  hasPassword: boolean
  passwordMasked: string | null
  network: string | null
  serverId: string | null
  source: string
  running: boolean
  /** RUNNING / STOPPED / UNKNOWN / MISSING（缺连接信息） */
  status: 'RUNNING' | 'STOPPED' | 'UNKNOWN' | 'MISSING'
  missingFields: string[]
  lastCheckAt: number
  lastError: string | null
  /** 是否被本项目变量引用（仅 effective 视图返回；未引用不计入健康） */
  referenced?: boolean
}

/** 环境模板变量 / 项目覆盖变量（敏感项 value 为掩码）。 */
export interface EnvVariableView {
  projectId: string | null
  env: string
  key: string
  secret: boolean
  value: string
  /** RESOURCE = 由中间件连接信息自动保存 */
  source: 'TEMPLATE' | 'RESOURCE' | 'OVERRIDE'
  updatedAt: number
}

/** 项目源码里声明的变量（.env / .env.example）；敏感名的 value 为掩码。 */
export interface SourceEnvVariableView {
  key: string
  value: string
  secret: boolean
  /** 有效变量里已有该键（模板/资源/覆盖优先于源码值） */
  overridden: boolean
}

export interface EnvRuntimeResult {
  ok: boolean
  message: string
  started: string[]
  stopped: string[]
  failed: string[]
  id?: string
  running?: boolean
  status?: EnvResourceView['status']
}

export interface EnvResourceBrief {
  id: string
  name: string
  kind: string
  status: EnvResourceView['status']
  missingFields: string[]
  host: string
  port: number
}

/** 项目注入前自检：只有缺变量 / 中间件缺连接信息才 ok=false。 */
export interface EnvCheckView {
  ok: boolean
  env: string
  projectId: string
  requiredCount: number
  missingVars: string[]
  /** 源码里已有值、无需在设置页填写的必填变量 */
  sourceProvidedVars: string[]
  missingResources: EnvResourceBrief[]
  stoppedResources: EnvResourceBrief[]
  referencedCount: number
  referencedRunningCount: number
  /** 被引用的中间件都可用（未启动不算失败，部署时会拉起） */
  middlewareHealthy: boolean
  problems: string[]
}

export interface EffectiveEnvironmentView {
  projectId: string
  env: string
  network: string
  defaultPort: number
  healthPath: string
  vars: EnvVariableView[]
  /** 项目源码（.env / .env.example）里声明的变量；部署时自动注入，人工覆盖优先 */
  sourceVars: SourceEnvVariableView[]
  templateCount: number
  overrideCount: number
  /** 被本项目引用的中间件数量（健康分母） */
  referencedCount: number
  referencedRunningCount: number
  resources: EnvResourceView[]
}

export interface EnvBindingView {
  id: string
  projectId: string
  env: string
  mappingJson: string
  mappingHash: string
  createdBy: string
  confirmedBy: string | null
  confirmedAt: number
  expiresAt: number
  status: string
}

export interface PortLeaseView {
  projectId: string
  env: string
  containerPort: number
  hostPort: number
  runId: string
  updatedAt: number
}

export function listEnvTemplates(): Promise<EnvTemplateRow[]> {
  return apiFetch('/api/v1/settings/envs')
}

export function getEnvDetail(env: string): Promise<{
  template: EnvTemplate
  resources: EnvResourceView[]
  vars: EnvVariableView[]
}> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}`)
}

export function saveEnvTemplate(
  env: string,
  input: {
    network?: string
    defaultTarget?: string
    defaultPort?: number
    healthPath?: string
    runtimeMode?: RuntimeMode
    namespace?: string
    serverId?: string | null
  }
): Promise<EnvTemplate> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function discoverEnvResources(
  env: string, source: 'local' | 'remote', serverId?: string
): Promise<Array<EnvResourceView & { candidateId: string }>> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}/discover`, {
    method: 'POST',
    body: JSON.stringify({ source, serverId: serverId ?? null })
  })
}

export function importEnvResources(env: string, ids: string[]): Promise<EnvResourceView[]> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}/import`, {
    method: 'POST',
    body: JSON.stringify({ ids })
  })
}

export function saveEnvResource(
  env: string,
  input: {
    id?: string
    kind?: string
    name?: string
    host?: string
    port?: number
    containerPort?: number
    internalHost?: string
    databaseName?: string
    username?: string
    password?: string
    network?: string
    serverId?: string
  }
): Promise<EnvResourceView> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}/resources`, {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

export function deleteEnvResource(env: string, id: string): Promise<void> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/resources/${encodeURIComponent(id)}`,
    { method: 'DELETE' }
  )
}

export function checkEnvResource(env: string, id: string): Promise<EnvResourceView> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/resources/${encodeURIComponent(id)}/check`,
    { method: 'POST' }
  )
}

/** 启动中间件 docker 容器（手工登记的资源会被拒绝）。 */
export function startEnvResource(env: string, id: string): Promise<EnvRuntimeResult> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/resources/${encodeURIComponent(id)}/start`,
    { method: 'POST' }
  )
}

export function stopEnvResource(env: string, id: string): Promise<EnvRuntimeResult> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/resources/${encodeURIComponent(id)}/stop`,
    { method: 'POST' }
  )
}

/** 查看模板变量明文（含中间件自动保存的账号密码）；需 settings:write，会写审计日志。 */
export function revealEnvVar(env: string, key: string): Promise<{ value: string }> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/vars/${encodeURIComponent(key)}/reveal`
  )
}

/** 查看项目最终注入值的明文（模板 / 资源 / 覆盖任一来源）。 */
export function revealEffectiveEnvVar(
  projectId: string, env: string, key: string
): Promise<{ value: string }> {
  const q = new URLSearchParams({ env, key })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/effective/reveal?${q}`
  )
}

/** 项目注入前自检：未启动不算异常，缺变量 / 缺连接信息才算。 */
export function checkProjectEnv(projectId: string, env: string): Promise<EnvCheckView> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/env-check?${q}`)
}

export function listEnvTemplateVars(env: string): Promise<EnvVariableView[]> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}/vars`)
}

export function saveEnvTemplateVar(
  env: string, input: { key: string, value?: string, secret?: boolean }
): Promise<EnvVariableView> {
  return apiFetch(`/api/v1/settings/envs/${encodeURIComponent(env)}/vars`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function deleteEnvTemplateVar(env: string, key: string): Promise<void> {
  return apiFetch(
    `/api/v1/settings/envs/${encodeURIComponent(env)}/vars/${encodeURIComponent(key)}`,
    { method: 'DELETE' }
  )
}

export function listEnvOverrides(projectId: string, env: string): Promise<EnvVariableView[]> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/overrides?${q}`)
}

export function saveEnvOverride(
  projectId: string, input: { env: string, key: string, value?: string, secret?: boolean }
): Promise<EnvVariableView> {
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/overrides`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

export function deleteEnvOverride(projectId: string, env: string, key: string): Promise<void> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/overrides/${encodeURIComponent(key)}?${q}`,
    { method: 'DELETE' }
  )
}

export function getEffectiveEnvironment(
  projectId: string, env: string
): Promise<EffectiveEnvironmentView> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/effective?${q}`)
}

export function draftEnvBinding(projectId: string, env: string): Promise<EnvBindingView> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/env-binding/draft?${q}`,
    { method: 'POST' }
  )
}

export function confirmEnvBinding(projectId: string, bindingId: string): Promise<EnvBindingView> {
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/env-binding/${encodeURIComponent(bindingId)}/confirm`,
    { method: 'POST' }
  )
}

export function getEnvBinding(projectId: string, bindingId: string): Promise<EnvBindingView> {
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/env-binding/${encodeURIComponent(bindingId)}`
  )
}

export function listPortLeases(projectId: string, env: string): Promise<PortLeaseView[]> {
  const q = new URLSearchParams({ projectId, env })
  return apiFetch(`/api/v1/deploy/ports?${q}`)
}

// ---------------------------------------------------------------- dev 本地服务（前后端进程）

export interface LocalServiceView {
  service: 'backend' | 'frontend'
  env: string
  port: number
  /** RUNNING / STOPPED / UNKNOWN / EXTERNAL（端口被非平台进程占用） */
  status: 'RUNNING' | 'STOPPED' | 'UNKNOWN' | 'EXTERNAL'
  pid: number
  updatedAt: number
  url: string
  /** 仓库里是否具备启动条件（缺 jar / 缺 npm 时为 false） */
  supported: boolean
  /** 启动条件说明或不可启动的原因 */
  hint: string
}

export interface LocalServiceResult {
  ok: boolean
  message: string
  port: number
  pid: number
  status: LocalServiceView['status']
  services: LocalServiceView[]
}

export function listLocalServices(projectId: string, env = 'dev'): Promise<LocalServiceView[]> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/services?${q}`)
}

/** 最近启动日志（启动失败时定位原因）。 */
export function getLocalServiceLogs(
  projectId: string, service: string, env = 'dev', lines = 120
): Promise<{ service: string, lines: string[] }> {
  const q = new URLSearchParams({ env, lines: String(lines) })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(service)}/logs?${q}`
  )
}

export function startLocalService(
  projectId: string, service: string, env = 'dev'
): Promise<LocalServiceResult> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(service)}/start?${q}`,
    { method: 'POST' }
  )
}

export function stopLocalService(
  projectId: string, service: string, env = 'dev'
): Promise<LocalServiceResult> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(service)}/stop?${q}`,
    { method: 'POST' }
  )
}

export function restartLocalService(
  projectId: string, service: string, env = 'dev'
): Promise<LocalServiceResult> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(service)}/restart?${q}`,
    { method: 'POST' }
  )
}

// ---------------------------------------------------------------- 项目密钥库

/**
 * 项目密钥条目（只返回名称 + 版本 + 掩码，明文永远不出服务端）。
 *
 * <p>环境变量写 `secret://name` 即可引用；轮换后版本号 +1，
 * 部署预检会用新版本，摘要也随版本变化。
 */
export interface ProjectSecret {
  projectId: string
  env: string
  name: string
  version: number
  /** 固定为 **** */
  value: string
  updatedAt: number
}

export function listProjectSecrets(projectId: string, env: string): Promise<ProjectSecret[]> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/secrets?${q}`)
}

export function saveProjectSecret(
  projectId: string, input: { env: string, name: string, value: string }
): Promise<ProjectSecret> {
  return apiFetch(`/api/v1/settings/projects/${encodeURIComponent(projectId)}/secrets`, {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

export function rotateProjectSecret(
  projectId: string, name: string, env: string, value: string
): Promise<ProjectSecret> {
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/secrets/${encodeURIComponent(name)}/rotate`,
    { method: 'POST', body: JSON.stringify({ env, value }) }
  )
}

export function deleteProjectSecret(
  projectId: string, name: string, env: string
): Promise<void> {
  const q = new URLSearchParams({ env })
  return apiFetch(
    `/api/v1/settings/projects/${encodeURIComponent(projectId)}/secrets/${encodeURIComponent(name)}?${q}`,
    { method: 'DELETE' }
  )
}

// ---------------------------------------------------------------- 配置解析（事实层）

export interface ConfigCandidateView {
  value: string
  source: string
  secret: boolean
  envHint: string
}

export interface ConfigCandidateGroup {
  key: string
  options: ConfigCandidateView[]
}

/**
 * 配置解析结果：项目环境优先，其次仓库声明，最后全量扫描的候选。
 *
 * <p>`NEED_USER_INPUT` 表示还缺配置或存在多个候选——此时 Agent 会停下来提问，
 * 用户回答后再把选择写入项目环境覆盖（或建密钥引用）。
 */
export interface ConfigResolutionView {
  status: 'READY' | 'NEED_USER_INPUT'
  projectId: string
  env: string
  resolved: Record<string, string>
  resolvedSource: Record<string, string>
  missing: string[]
  conflicts: string[]
  scannedFiles: number
  candidates: ConfigCandidateGroup[]
  summary: string
}

export function resolveProjectConfig(
  projectId: string, env: string, keys?: string[]
): Promise<ConfigResolutionView> {
  const q = new URLSearchParams({ env })
  if (keys && keys.length) q.set('keys', keys.join(','))
  return apiFetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/config/resolve?${q}`
  )
}

// ---------------------------------------------------------------- 分环境策略

export interface EnvPolicyView {
  L0: string
  L1: string
  L2: string
  L3: string
  autoApplyAfterDryRun: boolean
  requireBinding: boolean
}

export interface PoliciesView {
  envs: string[]
  /** env → 分级策略（ALLOW / CONFIRM / DENY）。 */
  policies: Record<string, EnvPolicyView>
}

export function listPolicies(): Promise<PoliciesView> {
  return apiFetch('/api/v1/policies')
}

// ---------------------------------------------------------------- 代码托管（GitHub / GitLab / 自定义）

export type GitHostProvider = 'GITHUB' | 'GITLAB' | 'CUSTOM'

export interface GitHostAccount {
  id: string
  provider: GitHostProvider
  host: string
  username: string
  hasToken: boolean
  tokenMasked: string | null
  updatedAt: number
}

export function listGitHosts(): Promise<GitHostAccount[]> {
  return apiFetch('/api/v1/settings/git-hosts')
}

/** id 为空 = 新增；同 provider + host 已存在时覆盖。token 留空 = 不修改。 */
export function saveGitHost(input: {
  id?: string
  provider: GitHostProvider
  host: string
  username?: string
  token?: string
}): Promise<GitHostAccount> {
  return apiFetch('/api/v1/settings/git-hosts', {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

export function deleteGitHost(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/git-hosts/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testGitHost(id: string): Promise<TestResult & { login?: string }> {
  return apiFetch(`/api/v1/settings/git-hosts/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

/** git ls-remote 验证仓库可达（accountId 为空时按 URL host 自动匹配凭据）。 */
export function testGitRepo(repoUrl: string, accountId?: string): Promise<TestResult & {
  branchCount?: number
  branches?: string[]
}> {
  return apiFetch('/api/v1/settings/git-hosts/test-repo', {
    method: 'POST',
    body: JSON.stringify({ repoUrl, accountId })
  })
}

// ---------------------------------------------------------------- Git 平台 OAuth 应用配置

export type GitOauthProvider = 'GITEE' | 'GITHUB' | 'GITLAB'

/**
 * 平台级 OAuth 应用配置：client-secret 只写不读，接口只回掩码。
 * 保存后「前往授权」立即生效，回调地址需与平台后台填写的完全一致。
 */
export interface GitOauthApp {
  provider: GitOauthProvider
  clientId: string
  redirectUri: string
  scopes: string
  enabled: boolean
  hasSecret: boolean
  secretMasked: string | null
  updatedAt: number
}

export function listGitOauthApps(): Promise<GitOauthApp[]> {
  return apiFetch('/api/v1/settings/git-oauth-apps')
}

/** clientSecret 留空 = 沿用已保存的 secret。 */
export function saveGitOauthApp(
  provider: GitOauthProvider,
  input: { clientId: string; clientSecret?: string; redirectUri: string; scopes: string; enabled: boolean }
): Promise<GitOauthApp> {
  return apiFetch(`/api/v1/settings/git-oauth-apps/${provider}`, {
    method: 'PUT',
    body: JSON.stringify(input)
  })
}

/** 清除某平台配置，回到环境变量兜底。 */
export function deleteGitOauthApp(provider: GitOauthProvider): Promise<void> {
  return apiFetch(`/api/v1/settings/git-oauth-apps/${provider}`, { method: 'DELETE' })
}

/** GitHub / GitLab OAuth 应用授权：返回授权页 URL，前端 window.open 后等回调。 */
export function gitOauthStart(provider: 'github' | 'gitlab'): Promise<{
  authorizeUrl: string
  state: string
  provider: string
}> {
  return apiFetch(`/api/v1/settings/git-hosts/oauth/${provider}/start`)
}

/** 解绑某平台的全部应用授权（等价于删除该平台下的托管账号）。 */
export function gitOauthUnbind(provider: 'github' | 'gitlab'): Promise<{
  ok: boolean
  removed: number
}> {
  return apiFetch(`/api/v1/settings/git-hosts/oauth/${provider}`, { method: 'DELETE' })
}

// ---------------------------------------------------------------- MCP

export type McpTransport = 'stdio' | 'http'

export interface McpServer {
  id: string
  name: string
  transport: McpTransport
  command: string | null
  args: string[]
  url: string | null
  headerNames: string[]
  enabled: boolean
  updatedAt: number
}

export interface McpServerInput {
  id?: string
  name: string
  transport: McpTransport
  command?: string
  args?: string
  url?: string
  /** 一行一个 Header：Key: Value；留空 = 不修改已保存的请求头 */
  headers?: string
  enabled?: boolean
}

export interface McpTestResult extends TestResult {
  serverName?: string
  serverVersion?: string
  protocolVersion?: string
}

export function listMcpServers(): Promise<McpServer[]> {
  return apiFetch('/api/v1/settings/mcp-servers')
}

export function saveMcpServer(input: McpServerInput): Promise<McpServer> {
  const { id, ...body } = input
  const path = id ? `/api/v1/settings/mcp-servers/${encodeURIComponent(id)}` : '/api/v1/settings/mcp-servers'
  return apiFetch(path, { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) })
}

export function deleteMcpServer(id: string): Promise<void> {
  return apiFetch(`/api/v1/settings/mcp-servers/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testMcpServer(id: string): Promise<McpTestResult> {
  return apiFetch(`/api/v1/settings/mcp-servers/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export function testMcpDraft(input: McpServerInput): Promise<McpTestResult> {
  return apiFetch('/api/v1/settings/mcp-servers/test', {
    method: 'POST',
    body: JSON.stringify(input)
  })
}

// ---------------------------------------------------------------- Skill

export interface SkillEntry {
  name: string
  description: string
  /** 目录名（接口标识；对话勾选用它） */
  dirName: string
  dir: string
  fileCount: number
  enabled: boolean
  updatedAt: number
}

export interface SkillList {
  root: string
  skills: SkillEntry[]
}

export function listSkills(): Promise<SkillList> {
  return apiFetch('/api/v1/settings/skills')
}

export function readSkill(name: string): Promise<{ name: string; content: string }> {
  return apiFetch(`/api/v1/settings/skills/${encodeURIComponent(name)}`)
}

export function saveSkill(input: {
  name: string
  description: string
  content: string
}): Promise<SkillEntry> {
  return apiFetch('/api/v1/settings/skills', { method: 'POST', body: JSON.stringify(input) })
}

export function importSkill(path: string): Promise<SkillEntry> {
  return apiFetch('/api/v1/settings/skills/import', {
    method: 'POST',
    body: JSON.stringify({ path })
  })
}

export function deleteSkill(name: string): Promise<void> {
  return apiFetch(`/api/v1/settings/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

/** 启用/停用技能：对话未显式勾选时按这里的默认状态加载。 */
export function setSkillEnabled(name: string, enabled: boolean): Promise<{
  ok: boolean
  name: string
  enabled: boolean
}> {
  return apiFetch(`/api/v1/settings/skills/${encodeURIComponent(name)}/enabled`, {
    method: 'POST',
    body: JSON.stringify({ enabled })
  })
}

// ---------------------------------------------------------------- 能力市场（MCP / Skill）

export interface CapabilityEntry {
  id: string
  kind: 'MCP' | 'SKILL'
  /** 安装后的标识：MCP 服务名 / Skill 目录名 */
  name: string
  title: string
  description: string
  tags: string[]
  source: string
  transport?: McpTransport
  command?: string | null
  args?: string[]
  url?: string | null
  installed: boolean
}

export interface CapabilityInstallResult {
  kind: string
  name: string
  installed: boolean
  message: string
}

export function listCapabilityMarket(
  kind?: 'MCP' | 'SKILL', q?: string
): Promise<CapabilityEntry[]> {
  const params = new URLSearchParams()
  if (kind) params.set('kind', kind)
  if (q && q.trim()) params.set('q', q.trim())
  const suffix = params.toString() ? `?${params}` : ''
  return apiFetch(`/api/v1/settings/capabilities/market${suffix}`)
}

export function installCapability(id: string): Promise<CapabilityInstallResult> {
  return apiFetch(`/api/v1/settings/capabilities/market/${encodeURIComponent(id)}/install`, {
    method: 'POST'
  })
}
