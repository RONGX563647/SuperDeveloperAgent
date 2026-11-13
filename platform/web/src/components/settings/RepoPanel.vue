<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Link, Plus, UserFilled } from '@element-plus/icons-vue'
import {
  giteeBind, giteeBindStatus, giteeOauthStart, giteeRepos, giteeUnbind,
  type GiteeBinding, type GiteeRepo
} from '../../api/project'
import {
  deleteGitHost, listGitHosts, saveGitHost, testGitHost, testGitRepo,
  type GitHostAccount, type GitHostProvider
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

function onProviderChange(value: GitHostProvider): void {
  const meta = PROVIDER_META[value]
  // 只在当前 host 为空或还是别家默认值时替换，避免覆盖用户填的自建域名
  const defaults = Object.values(PROVIDER_META).map((m) => m.host)
  if (!form.host.trim() || defaults.includes(form.host.trim())) {
    form.host = meta.host
  }
}

function openForm(): void {
  form.id = ''
  form.provider = 'GITHUB'
  form.host = 'github.com'
  form.username = ''
  form.token = ''
  formOpen.value = true
}

function editForm(account: GitHostAccount): void {
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
          Gitee 网页授权，或 GitHub / GitLab / 自建服务的访问令牌；token 加密保存，只回显掩码
        </div>
      </div>
      <el-button
        v-if="binding?.bound" size="small" :loading="reposLoading"
        @click="loadRepos"
      >
        刷新仓库
      </el-button>
    </div>

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

    <!-- GitHub / GitLab / 自定义 -->
    <section class="hosts">
      <div class="repos-head">
        <span class="repos-title">GitHub / GitLab / 自定义仓库</span>
        <span class="repos-hint">保存后 clone 与同步自动带凭据</span>
        <el-button size="small" style="margin-left: auto" @click="formOpen ? (formOpen = false) : openForm()">
          <el-icon><Plus /></el-icon> {{ formOpen ? '收起' : '新增接入' }}
        </el-button>
      </div>

      <div v-if="hostsLoading" class="repos-empty">加载中…</div>
      <div v-else-if="!hosts.length" class="repos-empty">
        还没有接入 GitHub / GitLab；私有仓库需要访问令牌。
      </div>
      <div v-for="h in hosts" :key="h.id" class="host-row">
        <span class="provider-badge" :class="h.provider.toLowerCase()">{{ providerLabel(h.provider) }}</span>
        <span class="host-name mono">{{ h.host }}</span>
        <span class="host-user mono">{{ h.username }}</span>
        <span class="host-token mono">{{ h.tokenMasked }}</span>
        <span class="host-actions">
          <el-button size="small" text :loading="testingHost === h.id" @click="testHost(h)">测试</el-button>
          <el-button size="small" text @click="editForm(h)">编辑</el-button>
          <el-button size="small" text type="danger" @click="removeHost(h)">删除</el-button>
        </span>
      </div>

      <div v-if="formOpen" class="host-form">
        <div class="form-row">
          <label class="form-label">平台</label>
          <el-select
            v-model="form.provider" size="default" class="form-control"
            @change="onProviderChange"
          >
            <el-option
              v-for="(meta, key) in PROVIDER_META" :key="key"
              :label="meta.label" :value="key"
            />
          </el-select>
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
        <div class="form-tip">{{ PROVIDER_META[form.provider].hint }}</div>
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
  gap: 8px;
  padding-top: 14px;
  border-top: 1px dashed var(--line-soft);
}
.host-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.provider-badge {
  flex: none;
  padding: 1px 8px;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.provider-badge.github {
  color: #24292f;
  background: #ebedf0;
}
.provider-badge.gitlab {
  color: #b3471d;
  background: #fdeee7;
}
.host-name {
  flex: none;
  font-size: 11.5px;
  color: var(--ink);
}
.host-user {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
.host-token {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.host-actions {
  display: flex;
  flex: none;
  gap: 2px;
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
