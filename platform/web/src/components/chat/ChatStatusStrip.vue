<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, ArrowRight, Check, CircleCheck, Connection, Cpu, Lock, Plus, Refresh, Warning
} from '@element-plus/icons-vue'
import { appStore, type PermissionMode } from '../../stores/app'
import {
  listModels, modelOptions, setDefaultModel,
  type ModelConnection, type ModelOptions
} from '../../api/settings'
import { gitCheckout, gitStatus, listProjects, type GitStatus, type ProjectRow } from '../../api/project'

/**
 * 对话状态条：上下文用量 / 权限 / 工作树 / 模型快捷切换。
 *
 * 四个 chip 共用一条浮层（一次只开一个），数据各自按需拉取：
 * - 上下文：由 ChatView 传入估算值（服务端目前不回精确 token）；
 * - 权限：登录身份 + 本地执行策略，切换后由 ChatView 决定确认卡行为；
 * - 工作树：分支选择会真实执行 git checkout；工作树只切换会话上下文；
 * - 模型：复用设置页的模型连接，切换即 setDefaultModel（运行时热切换）。
 */
interface Props {
  projectId: string
  /** 当前会话选中的工作树（空 = 主仓库）。 */
  worktree: string
  context: { used: number; limit: number; messages: number; events: number }
  permission: PermissionMode
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:permission': [value: PermissionMode]
  'update:worktree': [value: string]
  'new-session': []
  navigate: [path: string]
}>()

type PanelKey = 'context' | 'permission' | 'worktree' | 'model' | null
const panel = ref<PanelKey>(null)

function toggle(key: Exclude<PanelKey, null>): void {
  panel.value = panel.value === key ? null : key
  if (panel.value === 'model') void loadModels()
  if (panel.value === 'worktree') void loadGit()
}

// ---------------------------------------------------------------- 上下文
const pct = computed(() => {
  const limit = props.context.limit || 1
  return Math.min(100, Math.round((props.context.used / limit) * 100))
})

function formatK(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`
  return String(Math.round(n))
}

// ---------------------------------------------------------------- 权限
const identity = computed(() => appStore.identity)
const permissionSummary = computed(() => {
  const list = identity.value.permissions
  if (list.includes('*')) return '全部权限（*）'
  return list.length ? list.join('、') : '未加载'
})
const permissionLabel = computed(() => ({
  auto: '自动展开',
  confirm: '每次确认',
  readonly: '只读'
}[props.permission]))

const MODES: Array<{ value: PermissionMode; label: string; desc: string }> = [
  { value: 'auto', label: '自动展开', desc: '生成变更单时自动展开确认卡，减少来回点击' },
  { value: 'confirm', label: '每次确认', desc: '变更单出现时仅提示，由你手动展开确认' },
  { value: 'readonly', label: '只读诊断', desc: '不展开确认卡，本客户端不发起写操作' }
]

function chooseMode(mode: PermissionMode): void {
  emit('update:permission', mode)
}

// ---------------------------------------------------------------- 工作树
const projects = ref<ProjectRow[]>([])
const projectName = computed(() =>
  projects.value.find((p) => p.id === props.projectId)?.name ?? props.projectId)
const git = ref<GitStatus | null>(null)
const gitLoading = ref(false)
const gitError = ref(false)
/** 正在切换的分支（按钮级 loading，防止重复提交）。 */
const switchingBranch = ref('')

const worktreeLabel = computed(() => {
  if (gitLoading.value && !git.value) return '读取工作树…'
  if (gitError.value) return '工作树读取失败'
  if (!git.value?.initialized) return '工作树未接入'
  const branch = git.value.branch || 'HEAD'
  const dirty = git.value.uncommitted.length
  const scope = props.worktree ? `${props.worktree} · ` : ''
  return `${scope}${branch}${dirty ? ` · ${dirty} 改动` : ''}`
})

async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
  } catch {
    projects.value = []
  }
}

async function loadGit(): Promise<void> {
  if (!props.projectId) return
  gitLoading.value = true
  gitError.value = false
  try {
    git.value = await gitStatus(props.projectId)
  } catch {
    git.value = null
    gitError.value = true
  } finally {
    gitLoading.value = false
  }
}

/** 切换分支：真实执行 git checkout（有未提交改动时由 git 拒绝）。 */
async function pickBranch(branch: string): Promise<void> {
  if (switchingBranch.value || branch === git.value?.branch) return
  switchingBranch.value = branch
  try {
    await gitCheckout(props.projectId, branch, props.worktree)
    await loadGit()
    ElMessage.success(`已切换到分支 ${branch}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分支切换失败')
  } finally {
    switchingBranch.value = ''
  }
}

/** 切换工作树：只改变本会话的上下文，不做 checkout。 */
function pickWorktree(name: string): void {
  if (props.worktree === name) return
  emit('update:worktree', name)
}

watch(() => props.projectId, () => {
  git.value = null
  emit('update:worktree', '')
  void loadGit()
})

// ---------------------------------------------------------------- 模型
const models = ref<ModelConnection[]>([])
const active = ref<ModelOptions | null>(null)
const modelsLoading = ref(false)
const modelsError = ref('')
const switching = ref('')

const modelLabel = computed(() => {
  if (modelsError.value) return '模型服务异常'
  return active.value?.configured && active.value.modelName
    ? active.value.modelName
    : '未配置模型'
})

async function loadModels(): Promise<void> {
  modelsLoading.value = true
  modelsError.value = ''
  try {
    const [list, options] = await Promise.all([listModels(), modelOptions()])
    models.value = list
    active.value = options
  } catch (e) {
    models.value = []
    active.value = null
    modelsError.value = (e instanceof Error ? e.message : '模型接口不可用').slice(0, 120)
  } finally {
    modelsLoading.value = false
  }
}

async function pickModel(id: string): Promise<void> {
  if (switching.value) return
  switching.value = id
  try {
    await setDefaultModel(id)
    await loadModels()
    const picked = models.value.find((m) => m.id === id)
    ElMessage.success(`已切换模型：${picked?.name ?? id}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '模型切换失败')
  } finally {
    switching.value = ''
  }
}

function go(path: string): void {
  panel.value = null
  emit('navigate', path)
}

function newSession(): void {
  panel.value = null
  emit('new-session')
}

onMounted(() => {
  void loadProjects()
  void loadGit()
  void loadModels()
})
</script>

<template>
  <div class="status-strip">
    <div class="strip-row">
      <div class="strip-chips">
        <button
          type="button" class="status-chip" :class="{ on: panel === 'context' }"
          title="上下文使用" @click="toggle('context')"
        >
          <span class="mini-bar"><i :style="{ width: `${pct}%` }" /></span>
          <span>上下文 {{ pct }}%</span>
        </button>

        <button
          type="button" class="status-chip" :class="{ on: panel === 'permission' }"
          title="权限与执行策略" @click="toggle('permission')"
        >
          <el-icon><Lock /></el-icon>
          <span>{{ permissionLabel }}</span>
        </button>

        <button
          type="button" class="status-chip" :class="{ on: panel === 'worktree' }"
          :title="`工作树：${projectName}`" @click="toggle('worktree')"
        >
          <el-icon><Connection /></el-icon>
          <span>{{ worktreeLabel }}</span>
        </button>

        <button
          type="button" class="status-chip" :class="{ on: panel === 'model' }"
          title="模型快捷切换" @click="toggle('model')"
        >
          <el-icon><Cpu /></el-icon>
          <span>{{ modelLabel }}</span>
          <el-icon class="caret"><ArrowDown /></el-icon>
        </button>
      </div>
    </div>

    <!-- 点击空白关闭：比给每个 chip 挂 document 监听更好收口 -->
    <div v-if="panel" class="strip-backdrop" @click="panel = null" />
    <div v-if="panel" class="strip-panel" :class="{ 'align-right': panel === 'model' }">
      <!-- 上下文 -->
      <template v-if="panel === 'context'">
        <div class="panel-head">
          <b>上下文使用</b>
          <span class="mono panel-sub">{{ formatK(context.used) }} / {{ formatK(context.limit) }}</span>
        </div>
        <div class="ctx-progress"><i :style="{ width: `${pct}%` }" /></div>
        <div class="kv-list">
          <div><span>会话消息</span><b>{{ context.messages }} 条</b></div>
          <div><span>工具 / 流式事件</span><b>{{ context.events }} 次</b></div>
          <div><span>估算占用</span><b>{{ pct }}%</b></div>
        </div>
        <p class="panel-note">
          按消息字符数估算（含系统提示基线），非服务端精确 token；窗口按 {{ formatK(context.limit) }} 计。
        </p>
        <button type="button" class="panel-action" @click="newSession">
          <el-icon><Plus /></el-icon> 新建会话以释放上下文
        </button>
      </template>

      <!-- 权限 -->
      <template v-else-if="panel === 'permission'">
        <div class="panel-head">
          <b>权限与执行策略</b>
          <span class="panel-sub">{{ identity.role || '未登录' }}</span>
        </div>
        <div class="kv-list compact">
          <div><span>当前用户</span><b>{{ identity.displayName || '—' }} · {{ identity.userId || '—' }}</b></div>
          <div><span>角色</span><b>{{ identity.role || '—' }}</b></div>
          <div><span>权限码</span><b class="perm-codes" :title="permissionSummary">{{ permissionSummary }}</b></div>
        </div>
        <div class="panel-section-title">执行策略</div>
        <div class="mode-list">
          <button
            v-for="m in MODES" :key="m.value" type="button"
            class="mode-item" :class="{ active: permission === m.value }"
            @click="chooseMode(m.value)"
          >
            <span class="mode-radio"><el-icon v-if="permission === m.value"><Check /></el-icon></span>
            <span class="mode-copy"><b>{{ m.label }}</b><em>{{ m.desc }}</em></span>
          </button>
        </div>
        <p class="panel-note">执行策略作用于本客户端；所有写操作以后端「变更确认卡」为最终门禁。</p>
      </template>

      <!-- 工作树 -->
      <template v-else-if="panel === 'worktree'">
        <div class="panel-head">
          <b>工作树</b>
          <button
            type="button" class="panel-icon" :class="{ spin: gitLoading }"
            title="刷新 git 状态" :disabled="gitLoading" @click="loadGit"
          >
            <el-icon><Refresh /></el-icon>
          </button>
        </div>
        <div v-if="gitLoading && !git" class="panel-loading">正在读取 git 状态…</div>
        <template v-else-if="git?.initialized">
          <div class="kv-list compact">
            <div><span>项目</span><b>{{ projectName }} <em class="mono">{{ projectId }}</em></b></div>
            <div><span>分支</span><b class="mono">{{ git.branch || 'HEAD' }}</b></div>
            <div><span>HEAD</span><b class="mono">{{ git.head ? git.head.slice(0, 8) : '—' }}</b></div>
            <div><span>远程</span><b class="mono">{{ git.upstream || git.remotes?.[0]?.name || '未配置' }}</b></div>
            <div><span>领先 / 落后</span><b>{{ git.ahead ?? 0 }} / {{ git.behind ?? 0 }}</b></div>
          </div>
          <div class="panel-section-title">
            未提交改动 <span class="mono">{{ git.uncommitted.length }}</span>
          </div>
          <div v-if="git.uncommitted.length" class="file-list">
            <div v-for="f in git.uncommitted.slice(0, 8)" :key="f" class="file-row mono" :title="f.trim()">
              {{ f.trim() }}
            </div>
            <p v-if="git.uncommitted.length > 8" class="panel-note">
              还有 {{ git.uncommitted.length - 8 }} 条未显示
            </p>
          </div>
          <p v-else class="panel-note">工作区干净，没有未提交改动。</p>

          <div class="panel-section-title">
            分支 <span class="mono">{{ git.branches.length }}</span>
          </div>
          <div v-if="git.branches.length" class="branch-list">
            <button
              v-for="b in git.branches" :key="b" type="button"
              class="branch-item" :class="{ active: b === git.branch }"
              :disabled="!!switchingBranch" @click="pickBranch(b)"
            >
              <span class="branch-radio"><el-icon v-if="b === git.branch"><Check /></el-icon></span>
              <span class="mono branch-name">{{ b }}</span>
              <el-icon v-if="switchingBranch === b" class="spin"><Refresh /></el-icon>
              <span v-else-if="b === git.branch" class="branch-tag">当前</span>
            </button>
          </div>
          <p v-else class="panel-note">没有可切换的本地分支。</p>

          <div class="panel-section-title">
            工作树 <span class="mono">{{ worktree || '主仓库' }}</span>
          </div>
          <div class="file-list">
            <button
              type="button" class="wt-row" :class="{ active: !worktree }"
              @click="pickWorktree('')"
            >
              <b>主仓库</b><em>{{ git.branch || 'HEAD' }}</em>
            </button>
            <button
              v-for="w in git.worktrees.slice(0, 6)" :key="w.path" type="button"
              class="wt-row" :class="{ active: worktree === w.name }"
              @click="pickWorktree(w.name)"
            >
              <b>{{ w.name }}</b><em>{{ w.role }}</em>
            </button>
          </div>
          <p class="panel-note">
            分支切换会真实执行 git checkout；工作树只切换本会话的上下文提示。
          </p>
        </template>
        <div v-else-if="gitError" class="panel-empty">
          <el-icon><Warning /></el-icon>
          <p>git 状态接口不可用，可能后端刚重启或项目路径暂时不可读。</p>
          <button type="button" class="panel-action" @click="loadGit">
            <el-icon><Refresh /></el-icon> 重试
          </button>
        </div>
        <div v-else class="panel-empty">
          <el-icon><Warning /></el-icon>
          <p>该项目尚未接入本地 git 仓库，读不到分支、改动与 worktree。</p>
          <button type="button" class="panel-action" @click="go('/projects/intake')">
            去项目接入 <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </template>

      <!-- 模型 -->
      <template v-else-if="panel === 'model'">
        <div class="panel-head">
          <b>模型快捷切换</b>
          <span class="panel-sub">默认模型对新会话生效</span>
        </div>
        <div v-if="modelsError" class="panel-empty">
          <el-icon><Warning /></el-icon>
          <p>模型接口暂时不可用：{{ modelsError }}。常见于后端刚重启或工作目录 / 密钥未就绪。</p>
          <div class="panel-actions">
            <button type="button" class="panel-action" :disabled="modelsLoading" @click="loadModels">
              <el-icon :class="{ spin: modelsLoading }"><Refresh /></el-icon> 重试
            </button>
            <button type="button" class="panel-action ghost" @click="go('/settings?section=models')">
              去设置检查 <el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </div>
        <div v-else-if="!models.length" class="panel-empty">
          <el-icon><Warning /></el-icon>
          <p>还没有配置模型连接。到「设置 → 模型连接」添加后即可在这里一键切换。</p>
          <button type="button" class="panel-action" @click="go('/settings?section=models')">
            去添加模型连接 <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <template v-else>
          <div class="model-list">
            <button
              v-for="m in models" :key="m.id" type="button"
              class="model-item"
              :class="{ active: m.id === active?.activeId, muted: !m.enabled }"
              :disabled="!m.enabled || !!switching"
              @click="pickModel(m.id)"
            >
              <span class="model-main">
                <b>{{ m.name }}</b>
                <em class="mono">{{ m.modelName }}</em>
              </span>
              <span class="model-side">
                <span v-if="m.id === active?.activeId" class="default-tag">
                  <el-icon><CircleCheck /></el-icon> 当前默认
                </span>
                <span v-else class="model-provider">{{ m.provider }}</span>
                <el-icon v-if="switching === m.id" class="spin"><Refresh /></el-icon>
              </span>
            </button>
          </div>
          <p class="panel-note">切换后无需重启，新对话立即使用新模型（运行时热切换）。</p>
          <button type="button" class="panel-action" @click="go('/settings?section=models')">
            管理模型连接 <el-icon><ArrowRight /></el-icon>
          </button>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.status-strip {
  position: relative;
  max-width: 45rem;
  margin: 0 auto;
}
.strip-row {
  position: relative;
  z-index: 31; /* 高于 backdrop：浮层开着时也能直接点另一个 chip 切换 */
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 2px 0 6px;
}
.strip-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  min-width: 0;
}
.status-chip {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 6px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.status-chip:hover {
  color: var(--ink-soft);
  background: var(--surface-hover);
}
.status-chip.on {
  color: var(--ink);
  background: var(--surface-hover);
}
.status-chip .caret {
  font-size: 10px;
  opacity: 0.7;
}
.mini-bar {
  display: inline-flex;
  flex: none;
  width: 22px;
  height: 4px;
  overflow: hidden;
  background: var(--surface-active);
  border-radius: var(--radius-pill);
}
.mini-bar i {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: inherit;
  transition: width var(--dur-base) var(--ease-out);
}
/* ---- 浮层 ---- */
.strip-backdrop {
  position: fixed;
  inset: 0;
  z-index: 30;
}
.strip-panel {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  z-index: 31;
  width: min(380px, 100%);
  max-height: min(62vh, 470px);
  padding: 14px 16px 12px;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-3);
}
.strip-panel.align-right {
  right: 0;
  left: auto;
}
.panel-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-bottom: 10px;
  font-size: 13px;
  color: var(--ink);
}
.panel-head b {
  font-weight: 600;
}
.panel-sub {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--ink-muted);
}
.panel-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  margin-left: auto;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.panel-icon:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.panel-icon:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.ctx-progress {
  height: 6px;
  margin-bottom: 12px;
  overflow: hidden;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.ctx-progress i {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: inherit;
  transition: width var(--dur-base) var(--ease-out);
}
.kv-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.kv-list > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  font-size: 11.5px;
  border-bottom: 1px solid var(--line-soft);
}
.kv-list > div:last-child {
  border-bottom: 0;
}
.kv-list span {
  flex: none;
  color: var(--ink-muted);
}
.kv-list b {
  min-width: 0;
  overflow: hidden;
  font-weight: 500;
  color: var(--ink);
  text-align: right;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.kv-list b em {
  font-size: 10px;
  font-style: normal;
  color: var(--ink-faint);
}
.kv-list.compact > div {
  padding: 4px 0;
}
.perm-codes {
  max-width: 210px;
}
.panel-section-title {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 12px 0 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-soft);
}
.panel-section-title .mono {
  font-weight: 400;
  color: var(--ink-faint);
}
.panel-note {
  margin: 10px 0 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.panel-loading {
  padding: 14px 0;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.panel-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 6px 0 2px;
  color: var(--warn);
}
.panel-empty p {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-soft);
}
.panel-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  margin-top: 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--accent-soft);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.panel-action:hover {
  background: rgb(77 130 247 / 20%);
}
.panel-action:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.panel-action.ghost {
  color: var(--ink-muted);
  background: none;
  border-color: var(--line-soft);
}
.panel-action.ghost:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 执行策略 */
.mode-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.mode-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 8px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.mode-item:hover {
  background: var(--surface-hover);
}
.mode-item.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.mode-radio {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-top: 2px;
  font-size: 11px;
  color: #fff;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
}
.mode-item.active .mode-radio {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
.mode-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.mode-copy b {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.mode-copy em {
  font-size: 10.5px;
  font-style: normal;
  line-height: 1.5;
  color: var(--ink-muted);
}

/* 工作树文件列表 */
.file-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 150px;
  overflow-y: auto;
}
.file-row {
  padding: 4px 7px;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-soft);
  white-space: nowrap;
  background: var(--code-bg);
  border-radius: var(--radius-xs);
  text-overflow: ellipsis;
}
.wt-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 4px 7px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--code-bg);
  border: 0;
  border-radius: var(--radius-xs);
}
.wt-row:hover {
  background: var(--surface-hover);
}
.wt-row.active {
  background: var(--accent-soft);
}
.wt-row b {
  font-weight: 500;
}
.wt-row em {
  font-style: normal;
  color: var(--ink-faint);
}

/* 分支列表：点一下即 checkout */
.branch-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 132px;
  overflow-y: auto;
}
.branch-item {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 4px 7px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
  transition: background-color var(--dur-fast) ease;
}
.branch-item:hover:not(:disabled) {
  background: var(--surface-hover);
}
.branch-item.active {
  color: var(--ink);
  background: var(--accent-soft);
}
.branch-item:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.branch-radio {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 12px;
  font-size: 9px;
  color: #fff;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
}
.branch-item.active .branch-radio {
  background: var(--accent);
  border-color: var(--accent);
}
.branch-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.branch-tag {
  flex: none;
  font-size: 9.5px;
  color: var(--accent-ink);
}

/* 模型列表 */
.model-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.model-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.model-item:hover:not(:disabled) {
  background: var(--surface-hover);
}
.model-item.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.model-item:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.model-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.model-main b {
  overflow: hidden;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.model-main em {
  font-size: 10.5px;
  font-style: normal;
  color: var(--ink-muted);
}
.model-side {
  display: flex;
  flex: none;
  align-items: center;
  gap: 5px;
}
.default-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  font-size: 10px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.model-provider {
  font-size: 10px;
  color: var(--ink-faint);
}
.spin {
  animation: strip-spin 0.9s linear infinite;
}
@keyframes strip-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 720px) {
  .strip-panel,
  .strip-panel.align-right {
    right: auto;
    left: 0;
    width: 100%;
  }
}
</style>
