import { apiFetch } from './http'

// ---------------------------------------------------------------- 类型

/** 接入进度。phase: VALIDATING / LINKING / RECIPE_CHECK / REGISTERED / INTAKE_FAILED */
export interface IntakeStatus {
  projectId: string
  phase: string
  message: string
  report: Record<string, unknown>
  updatedAt: number
}

/** 自检报告（后端 recipeCheck 输出）。 */
export interface RecipeReport {
  actions: Record<string, string | null>
  pipeline: string | null
  /** 缺 pipeline.yml 时按仓库结构生成的 ci/pipeline.yml 内容。 */
  pipelineTemplate?: string
  /** 生成的完整文件清单（path + content）。 */
  scaffoldFiles?: Array<{ path: string; content: string }>
  /** 接入时实际落盘的文件（自动生成）。 */
  scaffoldWritten?: string[]
  stack?: string
  dockerfile: boolean
  ready: boolean
  warnings: string[]
}

/** git 远程。 */
export interface GitRemote {
  name: string
  url: string
}

export interface GitCommit {
  sha: string
  author: string
  relativeTime: string
  subject: string
}

/** git 状态。initialized=false 表示项目尚未完成 git 接入（前端显示接入引导）。 */
export interface GitStatus {
  initialized: boolean
  storageMode?: string
  head: string
  branch?: string
  branches: string[]
  /** 已配置的远程（origin 等）。 */
  remotes?: GitRemote[]
  /** 当前分支的 upstream（如 origin/main）；无则为 null。 */
  upstream?: string | null
  /** 相对 upstream 的领先提交数。 */
  ahead?: number
  /** 相对 upstream 的落后提交数。 */
  behind?: number
  uncommitted: string[]
  worktrees: WorktreeRow[]
  /** 最近提交（最多 5 条）。 */
  recentCommits?: GitCommit[]
}

/** worktree 行。role: BUILD / VERIFY / USER */
export interface WorktreeRow {
  path: string
  head: string
  role: string
  name: string
}

/** diff 结果。 */
export interface GitDiff {
  commits: string[]
  stat: string[]
}

// ---------------------------------------------------------------- 接入

export function intakeLocal(p: {
  projectId: string
  localPath: string
  type?: string
  gitInitIfMissing?: boolean
  /** DIRECT（默认）：仓库留在用户目录，平台就地操作。 */
  storageMode?: 'DIRECT' | 'MANAGED'
}): Promise<IntakeStatus> {
  return apiFetch('/api/v1/projects/intake/local', {
    method: 'POST',
    body: JSON.stringify(p)
  })
}

export function intakeRemote(p: {
  projectId: string
  repoUrl: string
  type?: string
  /** DIRECT：clone 到 cloneTargetDir（用户选的本地目录）。 */
  storageMode?: 'DIRECT' | 'MANAGED'
  cloneTargetDir?: string
}): Promise<IntakeStatus> {
  return apiFetch('/api/v1/projects/intake/remote', {
    method: 'POST',
    body: JSON.stringify(p)
  })
}

export function intakeStatus(projectId: string): Promise<IntakeStatus> {
  return apiFetch(`/api/v1/projects/intake/${encodeURIComponent(projectId)}/status`)
}

export function intakeValidate(p: {
  projectId: string
  localPath?: string
  repoUrl?: string
  type?: string
}): Promise<{ ok: boolean; error?: string }> {
  return apiFetch('/api/v1/projects/intake/validate', {
    method: 'POST',
    body: JSON.stringify(p)
  })
}

// ---------------------------------------------------------------- 文件浏览（本机后端）

export interface FsEntry {
  name: string
  path: string
  directory: boolean
  gitRepo: boolean
}

export interface FsListing {
  path: string
  parent: string | null
  root: string
  sharedRoot: string | null
  entries: FsEntry[]
}

export function fsLs(path?: string): Promise<FsListing> {
  const q = path ? `?path=${encodeURIComponent(path)}` : ''
  return apiFetch(`/api/v1/fs/ls${q}`)
}

export function fsProbe(path: string): Promise<{
  exists: boolean
  git: boolean
  normalized: string
  withinSharedRoot?: boolean
}> {
  return apiFetch(`/api/v1/fs/probe?path=${encodeURIComponent(path)}`)
}

// ---------------------------------------------------------------- 快捷位置（选择器侧栏）

export interface FsShortcut {
  key: string
  label: string
  path: string
  kind: string
}

export interface FsRoots {
  shortcuts: FsShortcut[]
  sharedRoot: string | null
}

export function fsRoots(): Promise<FsRoots> {
  return apiFetch('/api/v1/fs/roots')
}

/** 文件元数据 + 文本内容（文件预览用；文本截断由后端负责）。 */
export interface FsFile {
  path: string
  name: string
  sizeBytes: number
  modifiedAt: number
  /** 后端判定为二进制或超大文件时 content 为 null。 */
  content: string | null
  truncated: boolean
}

export function fsRead(path: string): Promise<FsFile> {
  return apiFetch(`/api/v1/fs/read?path=${encodeURIComponent(path)}`)
}

// ---------------------------------------------------------------- 脚手架（生成流水线脚本）

/** 一个将生成的文件（path 相对仓库根）。 */
export interface ScaffoldFile {
  path: string
  content: string
}

/** 脚手架预览结果。stack: JAVA / NODE / JAVA_NODE / UNKNOWN。 */
export interface ScaffoldPlan {
  projectId: string
  stack: string
  root: string
  target: string
  files: ScaffoldFile[]
  notes: string[]
}

/** 预览：扫描仓库结构，返回将生成的 ci/ 文件（不写盘）。 */
export function scaffoldPreview(projectId: string, target: string): Promise<ScaffoldPlan> {
  const q = new URLSearchParams({ target })
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/scaffold?${q}`)
}

/** 保存：默认存到平台侧（生成即可运行）；mode='repo' 才写进仓库 ci/（需自行提交）。 */
export function scaffoldApply(
  projectId: string,
  target: string,
  overwrite = false,
  mode: 'platform' | 'repo' = 'platform'
): Promise<{
  savedTo: string
  written: string[]
  skipped: string[]
  notes: string[]
  note?: string
  stack?: string
}> {
  const q = new URLSearchParams({ target, overwrite: String(overwrite), mode })
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/scaffold?${q}`, {
    method: 'POST'
  })
}

// ---------------------------------------------------------------- Gitee 账号

export interface GiteeBinding {
  bound: boolean
  giteeLogin?: string
  tokenMasked?: string
  boundAt?: number
}

export function giteeBindStatus(): Promise<GiteeBinding> {
  return apiFetch('/api/v1/gitee/bind/status')
}

export function giteeBind(token: string): Promise<{ ok: boolean; giteeLogin: string }> {
  return apiFetch('/api/v1/gitee/bind', { method: 'POST', body: JSON.stringify({ token }) })
}

export function giteeUnbind(): Promise<{ ok: boolean }> {
  return apiFetch('/api/v1/gitee/bind', { method: 'DELETE' })
}

export function giteeOauthStart(): Promise<{ authorizeUrl: string; state: string }> {
  return apiFetch('/api/v1/gitee/oauth/start')
}

export interface GiteeRepo {
  fullName: string
  cloneUrl: string
  privateRepo: boolean
  description: string
  updatedAt: string
}

export function giteeRepos(scope: 'personal' | 'all' = 'personal'): Promise<GiteeRepo[]> {
  return apiFetch(`/api/v1/gitee/repos?scope=${scope}`)
}

// ---------------------------------------------------------------- 列表 / 下线

/** 项目行。source: PRESET（yml）/ LOCAL / REMOTE（接入）。 */
export interface ProjectRow {
  id: string
  name: string
  type: string
  repoUrl: string | null
  source: string
  localPath: string | null
  intake: boolean
}

export function listProjects(): Promise<ProjectRow[]> {
  return apiFetch('/api/v1/projects')
}

/** 项目已配置的环境（对话页环境下拉用，只含真实存在的环境）。 */
export interface ProjectEnvRow {
  env: string
  appUrl: string
  composeDir: string
}

export function projectEnvs(projectId: string): Promise<ProjectEnvRow[]> {
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/envs`)
}

export function decommission(projectId: string): Promise<{ ok: boolean; note: string }> {
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' })
}

// ---------------------------------------------------------------- git

export function gitStatus(projectId: string): Promise<GitStatus> {
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/git/status`)
}

export function gitDiff(projectId: string, from: string, to: string): Promise<GitDiff> {
  const q = new URLSearchParams({ from, to })
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/git/diff?${q}`)
}

export function gitSync(projectId: string): Promise<{ ok: boolean; head: string }> {
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/git/sync`, { method: 'POST' })
}

/** 切换分支的结果。 */
export interface GitCheckoutResult {
  ok: boolean
  branch: string
  worktree: string
  head: string
}

/**
 * 切换分支：DIRECT 在用户目录执行，MANAGED 在平台 worktree 执行。
 * worktree 为空 = 主仓库（DIRECT）或默认 worktree（MANAGED）。
 */
export function gitCheckout(
  projectId: string, branch: string, worktree = ''
): Promise<GitCheckoutResult> {
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/git/checkout`, {
    method: 'POST',
    body: JSON.stringify({ branch, worktree })
  })
}

export interface GitCommitsPage {
  commits: GitCommit[]
  hasMore: boolean
  skip: number
}

/** 提交历史（分页，limit 上限 200）。 */
export function gitCommits(
  projectId: string,
  limit = 20,
  skip = 0
): Promise<GitCommitsPage> {
  const q = new URLSearchParams({ limit: String(limit), skip: String(skip) })
  return apiFetch(`/api/v1/projects/${encodeURIComponent(projectId)}/git/commits?${q}`)
}

// ---------------------------------------------------------------- worktree

export function removeWorktree(projectId: string, name: string): Promise<{ ok: boolean }> {
  return apiFetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/worktrees/${encodeURIComponent(name)}`,
    { method: 'DELETE' }
  )
}
