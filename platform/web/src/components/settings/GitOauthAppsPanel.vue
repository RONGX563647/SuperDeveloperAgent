<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection } from '@element-plus/icons-vue'
import {
  deleteGitOauthApp, listGitOauthApps, saveGitOauthApp,
  type GitOauthApp, type GitOauthProvider
} from '../../api/settings'

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
</style>
