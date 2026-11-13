<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection } from '@element-plus/icons-vue'
import {
  deleteGitOauthApp, listGitOauthApps, saveGitOauthApp,
  type GitOauthApp, type GitOauthProvider
} from '../../api/settings'
import {
  deleteGitIssueIntegration, listGitIssueIntegrations, saveGitIssueIntegration,
  type GitIssueIntegrationView,
} from '../../api/issues'

/**
 * Git 应用授权（设置页 · 高级设置）。
 *
 * <p>每个平台一张卡：Client ID / Secret / 回调地址 / 授权范围 / 启用开关。
 * 保存后「代码仓库」里的网页授权立即使用这份配置；Secret 加密存储、只回掩码。
 */
interface ProviderMeta {
  key: GitOauthProvider
  label: string
  hint: string
}

const PROVIDERS: ProviderMeta[] = [
  {
    key: 'GITEE',
    label: 'Gitee',
    hint: 'Gitee → 设置 → 第三方应用 → 创建应用；回调地址填下方地址，权限勾选 projects。'
  },
  {
    key: 'GITHUB',
    label: 'GitHub',
    hint: 'GitHub → Settings → Developer settings → OAuth Apps → New OAuth App；Authorization callback URL 填下方地址。'
  },
  {
    key: 'GITLAB',
    label: 'GitLab',
    hint: 'GitLab → Preferences → Applications → New application；Redirect URI 填下方地址，scopes 勾选 read_user / api。'
  }
]

interface FormState {
  clientId: string
  clientSecret: string
  redirectUri: string
  scopes: string
  enabled: boolean
}

const apps = ref<Partial<Record<GitOauthProvider, GitOauthApp>>>({})
const forms = reactive<Record<GitOauthProvider, FormState>>({
  GITEE: emptyForm(),
  GITHUB: emptyForm(),
  GITLAB: emptyForm()
})
const loading = ref(false)
const saving = ref<GitOauthProvider | ''>('')
const clearing = ref<GitOauthProvider | ''>('')

// ---------------------------------------------------------------- Issue 双向同步

interface IssueFormState {
  enabled: boolean
  repoWhitelist: string
  autoCreateTask: boolean
  syncDirection: string
  conflictPolicy: string
  webhookSecret: string
}

const issueConfigs = ref<Partial<Record<GitOauthProvider, GitIssueIntegrationView>>>({})
const issueForms = reactive<Record<GitOauthProvider, IssueFormState>>({
  GITEE: emptyIssueForm(),
  GITHUB: emptyIssueForm(),
  GITLAB: emptyIssueForm()
})
const issueOpen = reactive<Record<GitOauthProvider, boolean>>({
  GITEE: false, GITHUB: false, GITLAB: false,
})
const issueSaving = ref<GitOauthProvider | ''>('')
const issueClearing = ref<GitOauthProvider | ''>('')

function emptyIssueForm(): IssueFormState {
  return {
    enabled: false,
    repoWhitelist: '',
    autoCreateTask: false,
    syncDirection: 'BIDIRECTIONAL',
    conflictPolicy: 'GIT_WINS',
    webhookSecret: '',
  }
}

function fillIssueForm(provider: GitOauthProvider, config: GitIssueIntegrationView | null): void {
  const form = issueForms[provider]
  form.enabled = config?.enabled ?? false
  form.repoWhitelist = config?.repoWhitelist ?? ''
  form.autoCreateTask = config?.autoCreateTask ?? false
  form.syncDirection = config?.syncDirection ?? 'BIDIRECTIONAL'
  form.conflictPolicy = config?.conflictPolicy ?? 'GIT_WINS'
  form.webhookSecret = ''
}

function issueStateText(provider: GitOauthProvider): string {
  const config = issueConfigs.value[provider]
  if (!config) return '未配置'
  return config.enabled ? '已启用' : '已停用'
}

function issueSecretPlaceholder(provider: GitOauthProvider): string {
  const config = issueConfigs.value[provider]
  if (!config?.hasWebhookSecret) return 'Webhook 签名密钥（只写不读）'
  return `已保存 ${config.webhookSecretMasked ?? '***'}，留空不修改`
}

function emptyForm(): FormState {
  return { clientId: '', clientSecret: '', redirectUri: '', scopes: '', enabled: true }
}

function fillForm(provider: GitOauthProvider, app: GitOauthApp | null): void {
  const form = forms[provider]
  form.clientId = app?.clientId ?? ''
  form.clientSecret = ''
  form.redirectUri = app?.redirectUri ?? ''
  form.scopes = app?.scopes ?? ''
  form.enabled = app?.hasSecret ? app.enabled : true
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const list = await listGitOauthApps()
    const map: Partial<Record<GitOauthProvider, GitOauthApp>> = {}
    for (const app of list) map[app.provider] = app
    apps.value = map
    for (const p of PROVIDERS) fillForm(p.key, map[p.key] ?? null)
  } catch {
    apps.value = {}
    for (const p of PROVIDERS) fillForm(p.key, null)
  } finally {
    loading.value = false
  }
  await loadIssues()
}

/** Issue 同步配置与 OAuth 配置分开加载：任一失败不影响另一块。 */
async function loadIssues(): Promise<void> {
  try {
    const list = await listGitIssueIntegrations()
    const map: Partial<Record<GitOauthProvider, GitIssueIntegrationView>> = {}
    for (const config of list) map[config.provider] = config
    issueConfigs.value = map
    for (const p of PROVIDERS) fillIssueForm(p.key, map[p.key] ?? null)
  } catch {
    issueConfigs.value = {}
    for (const p of PROVIDERS) fillIssueForm(p.key, null)
  }
}

function secretPlaceholder(provider: GitOauthProvider): string {
  const app = apps.value[provider]
  if (!app?.hasSecret) return '粘贴应用密钥（只写不读）'
  return `已保存 ${app.secretMasked ?? '***'}，留空不修改`
}

async function copyCallback(provider: GitOauthProvider): Promise<void> {
  const url = forms[provider].redirectUri.trim()
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('回调地址已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择地址')
  }
}

async function save(provider: GitOauthProvider): Promise<void> {
  const form = forms[provider]
  const label = PROVIDERS.find((p) => p.key === provider)?.label ?? provider
  if (!form.clientId.trim()) {
    ElMessage.warning(`请填写 ${label} 应用 Client ID`)
    return
  }
  if (!form.redirectUri.trim().startsWith('http')) {
    ElMessage.warning('回调地址必须是完整的 http(s) 地址')
    return
  }
  if (!apps.value[provider]?.hasSecret && !form.clientSecret.trim()) {
    ElMessage.warning('首次配置必须填写 Client Secret')
    return
  }
  saving.value = provider
  try {
    const saved = await saveGitOauthApp(provider, {
      clientId: form.clientId.trim(),
      clientSecret: form.clientSecret.trim() || undefined,
      redirectUri: form.redirectUri.trim(),
      scopes: form.scopes.trim(),
      enabled: form.enabled
    })
    apps.value = { ...apps.value, [provider]: saved }
    form.clientSecret = ''
    ElMessage.success(`${label} 应用配置已保存，可直接前往授权`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = ''
  }
}

async function clear(provider: GitOauthProvider): Promise<void> {
  const label = PROVIDERS.find((p) => p.key === provider)?.label ?? provider
  const ok = await ElMessageBox.confirm(
    `清除 ${label} 的应用配置后，将回退到环境变量（通常为空）。确认清除？`,
    '清除应用配置',
    { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  clearing.value = provider
  try {
    await deleteGitOauthApp(provider)
    await load()
    ElMessage.success('已清除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '清除失败')
  } finally {
    clearing.value = ''
  }
}

// ---------------------------------------------------------------- Issue 同步操作

function toggleIssue(provider: GitOauthProvider): void {
  issueOpen[provider] = !issueOpen[provider]
}

async function copyWebhook(provider: GitOauthProvider): Promise<void> {
  const url = issueConfigs.value[provider]?.webhookUrl
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('Webhook 地址已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择地址')
  }
}

async function saveIssue(provider: GitOauthProvider): Promise<void> {
  const form = issueForms[provider]
  const label = PROVIDERS.find((p) => p.key === provider)?.label ?? provider
  if (form.enabled && !issueConfigs.value[provider]?.hasWebhookSecret
    && !form.webhookSecret.trim()) {
    ElMessage.warning('启用前请填写 Webhook Secret，用于校验 Git 回调')
    return
  }
  issueSaving.value = provider
  try {
    const saved = await saveGitIssueIntegration(provider, {
      enabled: form.enabled,
      repoWhitelist: form.repoWhitelist.trim(),
      autoCreateTask: form.autoCreateTask,
      syncDirection: form.syncDirection,
      conflictPolicy: form.conflictPolicy,
      webhookSecret: form.webhookSecret.trim() || undefined,
    })
    issueConfigs.value = { ...issueConfigs.value, [provider]: saved }
    form.webhookSecret = ''
    ElMessage.success(`${label} Issue 同步配置已保存`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    issueSaving.value = ''
  }
}

async function clearIssue(provider: GitOauthProvider): Promise<void> {
  const label = PROVIDERS.find((p) => p.key === provider)?.label ?? provider
  const ok = await ElMessageBox.confirm(
    `清除 ${label} 的 Issue 同步配置后，webhook 将拒绝回调，已同步任务保留。确认清除？`,
    '清除 Issue 同步',
    { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  issueClearing.value = provider
  try {
    await deleteGitIssueIntegration(provider)
    await loadIssues()
    ElMessage.success('已清除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '清除失败')
  } finally {
    issueClearing.value = ''
  }
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">Git 应用授权</div>
        <div class="panel-desc">
          Gitee / GitHub / GitLab 的 OAuth 应用凭据；保存后「代码仓库」的网页授权立即生效。
          Client Secret 加密保存，只回显掩码。
        </div>
      </div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>

    <template v-else>
    <section v-for="p in PROVIDERS" :key="p.key" class="oauth-card">
      <div class="oauth-head">
        <span class="oauth-icon"><el-icon><Connection /></el-icon></span>
        <div class="oauth-title">
          <div class="oauth-name">{{ p.label }}</div>
          <div class="oauth-sub mono">
            {{ apps[p.key]?.clientId || '未配置 Client ID' }}
          </div>
        </div>
        <span class="oauth-state" :class="{ ok: apps[p.key]?.hasSecret, off: apps[p.key]?.hasSecret && !apps[p.key]?.enabled }">
          {{ apps[p.key]?.hasSecret ? (apps[p.key]?.enabled ? '已启用' : '已停用') : '未配置' }}
        </span>
        <div class="oauth-actions">
          <el-button
            v-if="apps[p.key]?.hasSecret" size="small" text type="danger"
            :loading="clearing === p.key" @click="clear(p.key)"
          >
            清除
          </el-button>
          <el-button size="small" type="primary" :loading="saving === p.key" @click="save(p.key)">
            保存
          </el-button>
        </div>
      </div>

      <p class="oauth-hint">{{ p.hint }}</p>

      <div class="oauth-fields">
        <div class="form-row">
          <label class="form-label">Client ID</label>
          <el-input
            v-model="forms[p.key].clientId" size="default" class="form-control mono"
            placeholder="OAuth 应用的 Client ID"
          />
        </div>
        <div class="form-row">
          <label class="form-label">Client Secret</label>
          <el-input
            v-model="forms[p.key].clientSecret" type="password" show-password size="default"
            class="form-control mono" :placeholder="secretPlaceholder(p.key)"
          />
        </div>
        <div class="form-row">
          <label class="form-label">回调地址</label>
          <div class="oauth-uri">
            <el-input
              v-model="forms[p.key].redirectUri" size="default" class="form-control mono"
              placeholder="https://your-host/api/v1/..."
            />
            <el-button size="default" :disabled="!forms[p.key].redirectUri" @click="copyCallback(p.key)">
              复制
            </el-button>
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">授权范围</label>
          <el-input
            v-model="forms[p.key].scopes" size="default" class="form-control mono"
            placeholder="空格分隔，留空使用默认值"
          />
        </div>
        <div class="form-row">
          <label class="form-label">网页授权</label>
          <div class="oauth-switch">
            <el-switch v-model="forms[p.key].enabled" />
            <span class="oauth-switch-text">
              {{ forms[p.key].enabled ? '启用：可发起应用授权' : '停用：仅保留配置' }}
            </span>
          </div>
        </div>
      </div>

      <!-- Issue 双向同步：与 OAuth 应用同卡，展开后配置 Webhook 与映射规则 -->
      <div class="issue-sync">
        <button type="button" class="issue-toggle" @click="toggleIssue(p.key)">
          <span class="issue-title">Issue 双向同步</span>
          <span
            class="issue-state"
            :class="{ ok: issueConfigs[p.key]?.enabled }"
          >{{ issueStateText(p.key) }}</span>
          <span class="issue-caret">{{ issueOpen[p.key] ? '收起' : '展开' }}</span>
        </button>

        <div v-if="issueOpen[p.key]" class="issue-body">
          <p class="issue-hint">
            在 {{ p.label }} 仓库的 Webhook 中填入下方地址，勾选 Issue / Issue Comment
            （GitLab 为 Issue / Note）事件；平台验签、去重后异步映射为任务，回调不跑模型。
          </p>
          <div class="form-row">
            <label class="form-label">回调地址</label>
            <div class="oauth-uri">
              <el-input
                :model-value="issueConfigs[p.key]?.webhookUrl || '保存一次配置后生成'"
                readonly size="default" class="form-control mono"
              />
              <el-button
                size="default" :disabled="!issueConfigs[p.key]?.webhookUrl"
                @click="copyWebhook(p.key)"
              >
                复制
              </el-button>
            </div>
          </div>
          <div class="form-row">
            <label class="form-label">Secret</label>
            <el-input
              v-model="issueForms[p.key].webhookSecret" type="password" show-password
              size="default" class="form-control mono"
              :placeholder="issueSecretPlaceholder(p.key)"
            />
          </div>
          <div class="form-row">
            <label class="form-label">仓库白名单</label>
            <el-input
              v-model="issueForms[p.key].repoWhitelist" size="default" class="form-control mono"
              placeholder="owner/repo，逗号分隔；* 表示全部"
            />
          </div>
          <div class="form-row">
            <label class="form-label">同步方向</label>
            <el-select v-model="issueForms[p.key].syncDirection" size="default" class="form-control">
              <el-option label="双向同步" value="BIDIRECTIONAL" />
              <el-option label="仅平台 → Git" value="PUSH_ONLY" />
              <el-option label="仅 Git → 平台" value="PULL_ONLY" />
            </el-select>
          </div>
          <div class="form-row">
            <label class="form-label">冲突策略</label>
            <el-select v-model="issueForms[p.key].conflictPolicy" size="default" class="form-control">
              <el-option label="Git 优先" value="GIT_WINS" />
              <el-option label="平台优先（冷却防回灌）" value="PLATFORM_WINS" />
            </el-select>
          </div>
          <div class="form-row">
            <label class="form-label">启用同步</label>
            <div class="issue-switch">
              <el-switch v-model="issueForms[p.key].enabled" />
              <span class="oauth-switch-text">
                {{ issueForms[p.key].enabled ? '接收 webhook 并双向同步' : '停用：不再处理回调' }}
              </span>
            </div>
          </div>
          <div class="form-row">
            <label class="form-label">自动建任务</label>
            <div class="issue-switch">
              <el-switch v-model="issueForms[p.key].autoCreateTask" />
              <span class="oauth-switch-text">
                {{ issueForms[p.key].autoCreateTask ? 'Git 新建 Issue 时自动生成平台任务' : '只同步已绑定任务' }}
              </span>
            </div>
          </div>
          <div class="issue-actions">
            <el-button
              v-if="issueConfigs[p.key]" size="small" text type="danger"
              :loading="issueClearing === p.key" @click="clearIssue(p.key)"
            >
              清除同步配置
            </el-button>
            <el-button
              size="small" type="primary" :loading="issueSaving === p.key"
              @click="saveIssue(p.key)"
            >
              保存同步配置
            </el-button>
          </div>
        </div>
      </div>
    </section>
    </template>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
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
.empty {
  padding: 18px 0;
  font-size: 12px;
  color: var(--ink-faint);
}
.oauth-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 14px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) ease;
}
.oauth-card:hover {
  border-color: var(--line);
}
.oauth-head {
  display: flex;
  gap: 10px;
  align-items: center;
}
.oauth-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: 14px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.oauth-title {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.oauth-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.oauth-sub {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oauth-state {
  flex: none;
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.oauth-state.ok {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.oauth-state.off {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.oauth-actions {
  display: flex;
  flex: none;
  gap: 6px;
  align-items: center;
}
.oauth-hint {
  margin: 0;
  padding: 7px 10px;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 6px;
}
.oauth-fields {
  display: grid;
  gap: 8px;
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

/* Issue 双向同步（同一张平台卡片内） */
.issue-sync {
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px dashed var(--line-soft);
}
.issue-toggle {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 2px 0;
  font-family: inherit;
  cursor: pointer;
  background: none;
  border: 0;
}
.issue-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.issue-state {
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.issue-state.ok {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.issue-caret {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.issue-body {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}
.issue-hint {
  padding: 7px 10px;
  margin: 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 6px;
}
.issue-switch {
  display: flex;
  gap: 8px;
  align-items: center;
}
.issue-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
  padding-top: 4px;
}
</style>
