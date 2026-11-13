<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Connection, Link, Plus, UserFilled } from '@element-plus/icons-vue'
import {
  giteeBind, giteeBindStatus, giteeOauthStart, giteeRepos, giteeUnbind,
  type GiteeBinding, type GiteeRepo
} from '../../api/project'
import {
  deleteGitHost, deleteGitOauthApp, gitOauthStart, listGitHosts, listGitOauthApps, saveGitHost,
  saveGitOauthApp, testGitHost, testGitRepo,
  type GitHostAccount, type GitHostProvider, type GitOauthApp, type GitOauthProvider
} from '../../api/settings'

/**
 * 代码仓库授权面板（从「接入项目」页迁移过来）：Gitee 账号绑定 + OAuth + 私人令牌。
 *
 * <p>token 加密存 SQLite，接口只回掩码；绑定后可在这里预览仓库列表，
 * 「接入项目」页只负责选目录/选仓库，不再重复展示授权卡。
 */
const binding = ref<GiteeBinding | null>(null)
const patToken = ref('')
const patBusy = ref(false)
const oauthBusy = ref(false)
const repos = ref<GiteeRepo[]>([])
const reposLoading = ref(false)

async function load(): Promise<void> {
  try {
    binding.value = await giteeBindStatus()
  } catch {
    binding.value = { bound: false }
  }
  if (binding.value?.bound) {
    void loadRepos()
  } else {
    repos.value = []
  }
  void loadHosts()
  void loadOauthApps()
}

// ---------------------------------------------------------------- GitHub / GitLab / 自定义

const PROVIDER_META: Record<GitHostProvider, { label: string; host: string; hint: string }> = {
  GITHUB: { label: 'GitHub', host: 'github.com', hint: 'Settings → Developer settings → Personal access tokens（repo 权限）' },
  GITLAB: { label: 'GitLab', host: 'gitlab.com', hint: 'Preferences → Access Tokens（read_repository 权限）' },
  CUSTOM: { label: '自定义', host: '', hint: 'Gitea / Gogs / 自建 GitLab 等，填域名与访问令牌' }
}

const hosts = ref<GitHostAccount[]>([])
const hostsLoading = ref(false)
const hostSaving = ref(false)
const testingHost = ref('')
const formOpen = ref(false)
const form = reactive({
  id: '',
  provider: 'GITHUB' as GitHostProvider,
  host: 'github.com',
  username: '',
  token: ''
})

const probeUrl = ref('')
const probeBusy = ref(false)
const probe = ref<{ ok: boolean, message: string, branches?: string[] } | null>(null)
const hostOauthBusy = ref('')

// ---------------------------------------------------------------- 平台切换

type Platform = 'GITEE' | GitHostProvider

const PLATFORM_META: Record<Platform, { label: string, host: string, desc: string }> = {
  GITEE: { label: 'Gitee', host: 'gitee.com', desc: '网页授权或私人令牌，绑定后可浏览你的仓库列表' },
  GITHUB: { label: 'GitHub', host: 'github.com', desc: 'OAuth App 授权，或粘贴 Personal Access Token（repo 权限）' },
  GITLAB: { label: 'GitLab', host: 'gitlab.com', desc: 'OAuth App 授权，或粘贴 Access Token（read_repository 权限）' },
  CUSTOM: { label: '自定义', host: '', desc: 'Gitea / Gogs / 自建 GitLab 等，填写服务域名与访问令牌' }
}

const PLATFORM_ORDER: Platform[] = ['GITEE', 'GITHUB', 'GITLAB', 'CUSTOM']
const activePlatform = ref<Platform>('GITEE')
const hostPatToken = ref('')
const hostPatBusy = ref(false)

const platformAccounts = computed(() => {
  if (activePlatform.value === 'GITEE') return []
  return hosts.value.filter((h) => h.provider === activePlatform.value)
})

const activeMeta = computed(() => PLATFORM_META[activePlatform.value])
const activeProviderMeta = computed(() => PROVIDER_META[
  activePlatform.value === 'GITEE' ? 'GITHUB' : activePlatform.value
])

function platformCount(platform: Platform): number {
  if (platform === 'GITEE') return binding.value?.bound ? 1 : 0
  return hosts.value.filter((h) => h.provider === platform).length
}

function switchPlatform(platform: Platform): void {
  activePlatform.value = platform
  oauthOpen.value = false
  formOpen.value = false
  hostPatToken.value = ''
  if (platform === 'CUSTOM') {
    openForm()
    formOpen.value = true
  }
}

// ---------------------------------------------------------------- OAuth 应用配置

/** 各平台创建 OAuth 应用的位置，避免用户去猜。 */
const OAUTH_HINT: Record<GitOauthProvider, string> = {
  GITEE: 'Gitee → 设置 → 第三方应用 → 创建应用；回调地址填下方「回调地址」，权限勾选 projects。',
  GITHUB: 'GitHub → Settings → Developer settings → OAuth Apps → New OAuth App；Authorization callback URL 填下方「回调地址」。',
  GITLAB: 'GitLab → Preferences → Applications → New application；Redirect URI 填下方「回调地址」，scopes 勾选 read_user / api。'
}

const oauthApps = ref<Partial<Record<GitOauthProvider, GitOauthApp>>>({})
const oauthOpen = ref(false)
const oauthSaving = ref(false)
const oauthForm = reactive({
  clientId: '',
  clientSecret: '',
  redirectUri: '',
  scopes: '',
  enabled: true
})

const activeOauthApp = computed<GitOauthApp | null>(() => {
  const platform = activePlatform.value
  if (platform === 'CUSTOM') return null
  return oauthApps.value[platform] ?? null
})
const oauthHint = computed(() => {
  const platform = activePlatform.value
  return platform === 'CUSTOM' ? '' : OAUTH_HINT[platform]
})
const secretPlaceholder = computed(() => activeOauthApp.value?.hasSecret
  ? `已保存 ${activeOauthApp.value.secretMasked ?? '***'}，留空不修改`
  : '粘贴应用密钥（只写不读）')

async function loadOauthApps(): Promise<void> {
  try {
    const list = await listGitOauthApps()
    const map: Partial<Record<GitOauthProvider, GitOauthApp>> = {}
    for (const app of list) map[app.provider] = app
    oauthApps.value = map
  } catch {
    oauthApps.value = {}
  }
}

function toggleOauthConfig(): void {
  if (oauthOpen.value) {
    oauthOpen.value = false
    return
  }
  const app = activeOauthApp.value
  oauthForm.clientId = app?.clientId ?? ''
  oauthForm.clientSecret = ''
  oauthForm.redirectUri = app?.redirectUri ?? ''
  oauthForm.scopes = app?.scopes ?? ''
  oauthForm.enabled = app?.hasSecret ? app.enabled : true
  oauthOpen.value = true
}

async function saveOauthConfig(): Promise<void> {
  if (activePlatform.value === 'CUSTOM') return
  if (!oauthForm.clientId.trim()) {
    ElMessage.warning('请填写应用 Client ID')
    return
  }
  if (!oauthForm.redirectUri.trim().startsWith('http')) {
    ElMessage.warning('回调地址必须是完整的 http(s) 地址')
    return
  }
  if (!activeOauthApp.value?.hasSecret && !oauthForm.clientSecret.trim()) {
    ElMessage.warning('首次配置必须填写 Client Secret')
    return
  }
  const provider = activePlatform.value
  oauthSaving.value = true
  try {
    const saved = await saveGitOauthApp(provider, {
      clientId: oauthForm.clientId.trim(),
      clientSecret: oauthForm.clientSecret.trim() || undefined,
      redirectUri: oauthForm.redirectUri.trim(),
      scopes: oauthForm.scopes.trim(),
      enabled: oauthForm.enabled
    })
    oauthApps.value = { ...oauthApps.value, [provider]: saved }
    ElMessage.success(`${activeMeta.value.label} 应用配置已保存，可直接前往授权`)
    oauthOpen.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    oauthSaving.value = false
  }
}

async function copyCallback(): Promise<void> {
  const url = oauthForm.redirectUri.trim()
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('回调地址已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择地址')
  }
}

async function clearOauthConfig(): Promise<void> {
  const platform = activePlatform.value
  if (platform === 'CUSTOM') return
  const ok = await ElMessageBox.confirm(
    `清除 ${activeMeta.value.label} 的应用配置后，将回退到环境变量（通常为空）。确认清除？`,
    '清除应用配置',
    { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteGitOauthApp(platform)
    await loadOauthApps()
    oauthOpen.value = false
    ElMessage.success('已清除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '清除失败')
  }
}

/** 平台页内直接粘贴令牌绑定默认域名（GitHub / GitLab）。 */
async function bindHostPat(): Promise<void> {
  const token = hostPatToken.value.trim()
  if (!token || activePlatform.value === 'GITEE' || activePlatform.value === 'CUSTOM') return
  hostPatBusy.value = true
  try {
    await saveGitHost({
      provider: activePlatform.value as GitHostProvider,
      host: PLATFORM_META[activePlatform.value].host,
      token
    })
    ElMessage.success(`已绑定 ${activeMeta.value.label}，clone / 同步会自动带凭据`)
    hostPatToken.value = ''
    await loadHosts()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '绑定失败')
  } finally {
    hostPatBusy.value = false
  }
}

/** GitHub / GitLab 应用授权：打开授权页，回调后 token 加密写入托管账号。 */
async function bindOauth(provider: 'github' | 'gitlab'): Promise<void> {
  hostOauthBusy.value = provider
  try {
    const r = await gitOauthStart(provider)
    window.open(r.authorizeUrl, '_blank', 'noopener')
    ElMessage.info(`已打开 ${providerLabel(provider.toUpperCase() as GitHostProvider)} 授权页；`
      + '完成后回到本页点「刷新」')
  } catch (e) {
    const message = e instanceof Error ? e.message : '无法发起授权'
    // 未配置 OAuth App 时后端返回 501 的可读提示，直接展示给用户
    ElMessage.warning(message)
  } finally {
    hostOauthBusy.value = ''
  }
}

async function loadHosts(): Promise<void> {
  hostsLoading.value = true
  try {
    hosts.value = await listGitHosts()
  } catch {
    hosts.value = []
  } finally {
    hostsLoading.value = false
  }
}

function providerLabel(provider: GitHostProvider): string {
  return PROVIDER_META[provider]?.label ?? provider
}

function openForm(): void {
  const platform = activePlatform.value
  form.id = ''
  form.provider = platform === 'GITEE' ? 'GITHUB' : platform
  form.host = platform === 'GITEE' ? 'github.com' : PLATFORM_META[platform].host
  form.username = ''
  form.token = ''
  formOpen.value = true
}

function editForm(account: GitHostAccount): void {
  activePlatform.value = account.provider
  form.id = account.id
  form.provider = account.provider
  form.host = account.host
  form.username = account.username ?? ''
  form.token = ''
  formOpen.value = true
}

async function saveHost(): Promise<void> {
  const host = form.host.trim()
  if (!host) {
    ElMessage.warning('请填写服务域名，例如 github.com')
    return
  }
  if (!form.id && !form.token.trim()) {
    ElMessage.warning('首次接入必须填写访问令牌')
    return
  }
  hostSaving.value = true
  try {
    await saveGitHost({
      id: form.id || undefined,
      provider: form.provider,
      host,
      username: form.username.trim() || undefined,
      token: form.token.trim() || undefined
    })
    ElMessage.success('已保存，接入项目时会自动匹配凭据')
    formOpen.value = false
    await loadHosts()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    hostSaving.value = false
  }
}

async function removeHost(account: GitHostAccount): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除后 clone / 同步 ${account.host} 的私有仓库将不再自动带凭据。确认删除？`,
    '删除托管账号',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteGitHost(account.id)
    ElMessage.success('已删除')
    await loadHosts()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function testHost(account: GitHostAccount): Promise<void> {
  testingHost.value = account.id
  try {
    const r = await testGitHost(account.id)
    if (r.ok) ElMessage.success(r.message)
    else ElMessage.error(r.message)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    testingHost.value = ''
  }
}

async function probeRepo(): Promise<void> {
  const url = probeUrl.value.trim()
  if (!url) return
  probeBusy.value = true
  probe.value = null
  try {
    const r = await testGitRepo(url)
    probe.value = { ok: r.ok, message: r.message, branches: r.branches }
  } catch (e) {
    probe.value = { ok: false, message: e instanceof Error ? e.message : '测试失败' }
  } finally {
    probeBusy.value = false
  }
}

async function loadRepos(): Promise<void> {
  reposLoading.value = true
  try {
    repos.value = (await giteeRepos('personal')).slice(0, 6)
  } catch {
    repos.value = []
  } finally {
    reposLoading.value = false
  }
}

async function bindPat(): Promise<void> {
  const token = patToken.value.trim()
  if (!token) return
  patBusy.value = true
  try {
    const r = await giteeBind(token)
    ElMessage.success(`已绑定 Gitee 账号：${r.giteeLogin}`)
    patToken.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '绑定失败')
  } finally {
    patBusy.value = false
  }
}

async function oauthStart(): Promise<void> {
  oauthBusy.value = true
  try {
    const r = await giteeOauthStart()
    window.open(r.authorizeUrl, '_blank', 'noopener')
    ElMessage.info('已打开 Gitee 授权页；授权成功后回到本页点「刷新」')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '无法发起授权')
  } finally {
    oauthBusy.value = false
  }
}

async function unbind(): Promise<void> {
  const ok = await ElMessageBox.confirm(
    '解绑后需要重新授权才能浏览/克隆私有仓库。确认解绑？', '解绑 Gitee',
    { confirmButtonText: '解绑', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await giteeUnbind()
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解绑失败')
  }
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">代码仓库</div>
        <div class="panel-desc">
          按平台切换授权方式：Gitee 网页授权，GitHub / GitLab OAuth 或访问令牌，也支持自建服务
        </div>
      </div>
      <el-button
        v-if="activePlatform === 'GITEE' && binding?.bound" size="small" :loading="reposLoading"
        @click="loadRepos"
      >
        刷新仓库
      </el-button>
    </div>

    <!-- 平台切换：每个平台一个独立授权页 -->
    <div class="platform-tabs">
      <button
        v-for="p in PLATFORM_ORDER" :key="p" type="button" class="platform-tab"
        :class="{ active: activePlatform === p }"
        @click="switchPlatform(p)"
      >
        {{ PLATFORM_META[p].label }}
        <span v-if="platformCount(p)" class="tab-count mono">{{ platformCount(p) }}</span>
      </button>
    </div>

    <!-- 应用授权配置：三个平台共用一套表单，保存后「前往授权」立即生效 -->
    <section v-if="activePlatform !== 'CUSTOM'" class="oauth-app">
      <div class="oauth-app-head">
        <span class="oauth-app-title">应用授权配置</span>
        <span class="oauth-app-state" :class="{ ok: activeOauthApp?.hasSecret }">
          {{ activeOauthApp?.hasSecret ? '已配置' : '未配置' }}
        </span>
        <span class="oauth-app-sub mono">
          {{ activeOauthApp?.clientId || 'Client ID / Secret / 回调地址' }}
        </span>
        <span v-if="activeOauthApp?.hasSecret && !activeOauthApp.enabled" class="oauth-app-off">已停用</span>
        <button type="button" class="oauth-app-toggle" @click="toggleOauthConfig">
          {{ oauthOpen ? '收起' : '配置' }}
          <el-icon class="chev" :class="{ open: oauthOpen }"><ArrowDown /></el-icon>
        </button>
      </div>

      <div v-if="oauthOpen" class="oauth-app-body">
        <p class="oauth-app-hint">{{ oauthHint }}</p>
        <div class="form-row">
          <label class="form-label">Client ID</label>
          <el-input
            v-model="oauthForm.clientId" size="default" class="form-control mono"
            placeholder="OAuth 应用的 Client ID"
          />
        </div>
        <div class="form-row">
          <label class="form-label">Client Secret</label>
          <el-input
            v-model="oauthForm.clientSecret" type="password" show-password size="default"
            class="form-control mono" :placeholder="secretPlaceholder"
          />
        </div>
        <div class="form-row">
          <label class="form-label">回调地址</label>
          <div class="oauth-uri">
            <el-input
              v-model="oauthForm.redirectUri" size="default" class="form-control mono"
              placeholder="https://your-host/api/v1/..."
            />
            <el-button size="default" :disabled="!oauthForm.redirectUri" @click="copyCallback">
              复制
            </el-button>
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">授权范围</label>
          <el-input
            v-model="oauthForm.scopes" size="default" class="form-control mono"
            placeholder="空格分隔，留空使用默认值"
          />
        </div>
        <div class="form-row">
          <label class="form-label">网页授权</label>
          <div class="oauth-switch">
            <el-switch v-model="oauthForm.enabled" />
            <span class="oauth-switch-text">
              {{ oauthForm.enabled ? '启用：可发起应用授权' : '停用：仅保留配置' }}
            </span>
          </div>
        </div>
        <div class="form-actions">
          <el-button
            v-if="activeOauthApp?.hasSecret" size="default" text type="danger"
            class="danger-clear" @click="clearOauthConfig"
          >
            清除配置
          </el-button>
          <el-button size="default" @click="oauthOpen = false">取消</el-button>
          <el-button size="default" type="primary" :loading="oauthSaving" @click="saveOauthConfig">
            保存配置
          </el-button>
        </div>
      </div>
    </section>

    <template v-if="activePlatform === 'GITEE'">
    <!-- 已绑定 -->
    <section v-if="binding?.bound" class="account-card">
      <span class="acct-icon"><el-icon><UserFilled /></el-icon></span>
      <div class="acct-main">
        <div class="acct-name">Gitee：{{ binding.giteeLogin }}</div>
        <div class="acct-token mono">{{ binding.tokenMasked }}</div>
      </div>
      <el-tag size="small" type="success" round effect="light">已连接</el-tag>
      <el-button size="small" text type="danger" @click="unbind">解绑</el-button>
    </section>

    <!-- 未绑定 -->
    <template v-else>
      <section class="account-card muted">
        <span class="acct-icon"><el-icon><Link /></el-icon></span>
        <div class="acct-main">
          <div class="acct-name">未绑定 Gitee 账号</div>
          <div class="acct-token">绑定后可直接从你的仓库列表选择项目；token 加密存储</div>
        </div>
      </section>

      <div class="bind-grid">
        <div class="bind-block">
          <div class="bind-title">方式一：网页授权（推荐）</div>
          <p class="bind-desc">跳转 Gitee 授权页，回调后自动绑定，无需手动复制令牌。</p>
          <el-button size="small" type="primary" :loading="oauthBusy" @click="oauthStart">
            <el-icon><Connection /></el-icon> 前往授权
          </el-button>
        </div>
        <div class="bind-block">
          <div class="bind-title">方式二：私人令牌</div>
          <p class="bind-desc">在 Gitee「设置 → 私人令牌」创建，勾选 projects 权限。</p>
          <div class="pat-row">
            <el-input
              v-model="patToken" type="password" show-password size="default"
              class="pat-input mono" placeholder="粘贴私人令牌"
              @keydown.enter="bindPat"
            />
            <el-button size="default" :disabled="!patToken.trim() || patBusy" :loading="patBusy" @click="bindPat">
              绑定
            </el-button>
          </div>
        </div>
      </div>
    </template>

    <!-- 已授权仓库预览 -->
    <section v-if="binding?.bound" class="repos">
      <div class="repos-head">
        <span class="repos-title">你的仓库</span>
        <span class="repos-hint">最近更新的前 6 个；接入项目时可选任意仓库</span>
      </div>
      <div v-if="reposLoading" class="repos-empty">加载中…</div>
      <div v-else-if="!repos.length" class="repos-empty">没有可访问的仓库</div>
      <div v-for="r in repos" :key="r.fullName" class="repo-row">
        <span class="repo-name mono">{{ r.fullName }}</span>
        <span v-if="r.privateRepo" class="repo-tag warn">私有</span>
        <span class="repo-desc">{{ r.description }}</span>
      </div>
    </section>
    </template>

    <!-- GitHub / GitLab / 自定义：与 Gitee 共用一套授权卡片语言 -->
    <section v-else class="hosts">
      <div class="platform-desc">{{ activeMeta.desc }}</div>

      <div v-if="hostsLoading" class="repos-empty">加载中…</div>
      <template v-else>
        <section v-if="!platformAccounts.length" class="account-card muted">
          <span class="acct-icon"><el-icon><Link /></el-icon></span>
          <div class="acct-main">
            <div class="acct-name">未连接 {{ activeMeta.label }}</div>
            <div class="acct-token">
              {{ activePlatform === 'CUSTOM'
                ? '填写服务域名与访问令牌后，clone / 同步会自动带凭据'
                : '授权后自动带入凭据，无需在仓库地址里写 token' }}
            </div>
          </div>
        </section>

        <section v-for="h in platformAccounts" :key="h.id" class="account-card">
          <span class="acct-icon"><el-icon><Connection /></el-icon></span>
          <div class="acct-main">
            <div class="acct-name">{{ activeMeta.label }}：{{ h.username || h.host }}</div>
            <div class="acct-token mono">{{ h.host }} · {{ h.tokenMasked }}</div>
          </div>
          <el-tag size="small" type="success" round effect="light">已连接</el-tag>
          <div class="acct-actions">
            <el-button size="small" text :loading="testingHost === h.id" @click="testHost(h)">测试</el-button>
            <el-button size="small" text @click="editForm(h)">编辑</el-button>
            <el-button size="small" text type="danger" @click="removeHost(h)">解绑</el-button>
          </div>
        </section>
      </template>

      <div v-if="activePlatform !== 'CUSTOM'" class="bind-grid">
        <div class="bind-block">
          <div class="bind-title">方式一：网页授权（推荐）</div>
          <p class="bind-desc">跳转 {{ activeMeta.label }} 授权页，回调后自动绑定，无需手动复制令牌。</p>
          <el-button
            size="small" type="primary"
            :loading="hostOauthBusy === activePlatform.toLowerCase()"
            @click="bindOauth(activePlatform === 'GITHUB' ? 'github' : 'gitlab')"
          >
            <el-icon><Connection /></el-icon> 前往授权
          </el-button>
        </div>
        <div class="bind-block">
          <div class="bind-title">方式二：访问令牌</div>
          <p class="bind-desc">{{ activeProviderMeta.hint }}</p>
          <div class="pat-row">
            <el-input
              v-model="hostPatToken" type="password" show-password size="default"
              class="pat-input mono" placeholder="粘贴访问令牌"
              @keydown.enter="bindHostPat"
            />
            <el-button
              size="default" :disabled="!hostPatToken.trim() || hostPatBusy"
              :loading="hostPatBusy" @click="bindHostPat"
            >
              绑定
            </el-button>
          </div>
        </div>
      </div>

      <div class="hosts-extra">
        <el-button size="small" text @click="formOpen ? (formOpen = false) : openForm()">
          <el-icon><Plus /></el-icon>
          {{ formOpen ? '收起' : (activePlatform === 'CUSTOM' ? '新增自建服务' : '接入其他域名（企业版 / 自建）') }}
        </el-button>
      </div>

      <div v-if="formOpen" class="host-form">
        <div class="form-row">
          <label class="form-label">平台</label>
          <span class="form-static mono">{{ providerLabel(form.provider) }}</span>
        </div>
        <div class="form-row">
          <label class="form-label">服务域名</label>
          <el-input
            v-model="form.host" size="default" class="form-control mono"
            placeholder="github.com / gitlab.com / git.example.com"
          />
        </div>
        <div class="form-row">
          <label class="form-label">用户名</label>
          <el-input
            v-model="form.username" size="default" class="form-control mono"
            placeholder="可留空（GitHub 用 x-access-token，GitLab 用 oauth2）"
          />
        </div>
        <div class="form-row">
          <label class="form-label">访问令牌</label>
          <el-input
            v-model="form.token" type="password" show-password size="default"
            class="form-control mono"
            :placeholder="form.id ? '留空 = 不修改' : '粘贴 Personal Access Token'"
            @keydown.enter="saveHost"
          />
        </div>
        <div class="form-tip">{{ activeProviderMeta.hint }}</div>
        <div class="form-actions">
          <el-button size="default" @click="formOpen = false">取消</el-button>
          <el-button size="default" type="primary" :loading="hostSaving" @click="saveHost">
            保存
          </el-button>
        </div>
      </div>

      <div class="probe">
        <div class="probe-title">测试仓库可访问性</div>
        <div class="pat-row">
          <el-input
            v-model="probeUrl" size="default" class="pat-input mono"
            placeholder="https://github.com/org/repo.git"
            @keydown.enter="probeRepo"
          />
          <el-button
            size="default" :disabled="!probeUrl.trim() || probeBusy"
            :loading="probeBusy" @click="probeRepo"
          >
            测试
          </el-button>
        </div>
        <div v-if="probe" class="probe-result" :class="probe.ok ? 'ok' : 'fail'">
          {{ probe.message }}
          <span v-if="probe.branches?.length" class="probe-branches mono">
            {{ probe.branches.join(' · ') }}
          </span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.panel-title {
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.panel-desc {
  max-width: 46rem;
  margin-top: 4px;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.panel-head .el-button {
  margin-left: auto;
}

/* 平台切换：与 MCP / Skill 面板同一套 Tab 视觉 */
.platform-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--line-soft);
}
.platform-tab {
  padding: 6px 2px 7px;
  margin-right: 14px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
}
.platform-tab:hover {
  color: var(--ink);
}
.platform-tab.active {
  color: var(--ink);
  border-bottom-color: var(--accent);
}
.tab-count {
  margin-left: 3px;
  color: var(--ink-faint);
}
.platform-desc {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.account-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.account-card.muted {
  color: var(--ink-muted);
}
.acct-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 18px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 50%;
}
.acct-main {
  flex: 1;
  min-width: 0;
}
.acct-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.acct-token {
  margin-top: 2px;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.acct-actions {
  display: flex;
  flex: none;
  gap: 2px;
}
.bind-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}
.bind-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.bind-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.bind-desc {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.bind-block .el-button {
  align-self: flex-start;
}
.pat-row {
  display: flex;
  gap: 8px;
}
.pat-input {
  flex: 1;
  min-width: 0;
}
.repos {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 4px;
  border-top: 1px dashed var(--line-soft);
}
.repos-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.repos-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.repos-hint {
  font-size: 11px;
  color: var(--ink-faint);
}
.repos-empty {
  padding: 14px 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.repo-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 10px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.repo-name {
  flex: none;
  font-size: 11.5px;
  color: var(--ink);
}
.repo-tag.warn {
  flex: none;
  padding: 1px 6px;
  font-size: 10px;
  color: var(--warn);
  background: var(--warn-soft);
  border-radius: var(--radius-pill);
}
.repo-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ------------------------------ GitHub / GitLab / 自定义 ------------------------------ */
.hosts {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.acct-actions {
  display: flex;
  flex: none;
  gap: 2px;
}
.hosts-extra {
  display: flex;
  justify-content: flex-start;
}
.form-static {
  font-size: 12px;
  color: var(--ink);
}
.host-form {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.form-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}
.form-label {
  font-size: 12px;
  color: var(--ink-muted);
}
.form-control {
  width: 100%;
}
.form-tip {
  padding-left: 82px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.form-actions .danger-clear {
  margin-right: auto;
}
.oauth-app {
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.oauth-app-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 14px;
}
.oauth-app-title {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.oauth-app-state {
  flex: none;
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.oauth-app-state.ok {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.oauth-app-sub {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oauth-app-off {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
.oauth-app-toggle {
  display: inline-flex;
  flex: none;
  gap: 3px;
  align-items: center;
  margin-left: auto;
  padding: 4px 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: none;
  border-radius: 6px;
}
.oauth-app-toggle:hover {
  color: var(--ink);
  background: var(--surface-sunken);
}
.oauth-app-toggle .chev {
  transition: transform var(--dur-fast) var(--ease-out);
}
.oauth-app-toggle .chev.open {
  transform: rotate(180deg);
}
.oauth-app-body {
  display: grid;
  gap: 10px;
  padding: 12px 14px 14px;
  border-top: 1px solid var(--line-soft);
}
.oauth-app-hint {
  margin: 0;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 6px;
}
.oauth-uri {
  display: flex;
  gap: 8px;
}
.oauth-uri .form-control {
  flex: 1;
  min-width: 0;
}
.oauth-switch {
  display: flex;
  gap: 8px;
  align-items: center;
}
.oauth-switch-text {
  font-size: 11px;
  color: var(--ink-muted);
}
.probe {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 6px;
}
.probe-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
}
.probe-result {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 10px;
  font-size: 11.5px;
  border-radius: var(--radius-sm);
}
.probe-result.ok {
  color: var(--ink-soft);
  background: var(--accent-soft);
}
.probe-result.fail {
  color: var(--danger, #c45656);
  background: var(--danger-soft, #fdf0f0);
  white-space: pre-wrap;
}
.probe-branches {
  color: var(--ink-faint);
}
</style>
