<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import ResizableSplitter from '../components/project/ResizableSplitter.vue'
import TriggerBar from '../components/pipeline/TriggerBar.vue'
import HistoryList from '../components/pipeline/HistoryList.vue'
import ActionPanel from '../components/pipeline/ActionPanel.vue'
import StageTimeline from '../components/pipeline/StageTimeline.vue'
import RunLog from '../components/pipeline/RunLog.vue'
import { getHistory, listDeployTargets, listPipelines, triggerPipeline,
  type DeployTargetOption, type HistoryItem, type RunView } from '../api/pipeline'
import { gitCommits, listProjects, type GitCommit, type ProjectRow } from '../api/project'
import { getActionPlan, listActions, requestAction, runAction, type ActionView, type ActionRunView } from '../api/action'
import { applyChange, confirmChange, getChange } from '../api/change'
import type { ChangeView } from '../types'
import { appStore } from '../stores/app'
import { useRunTracker } from '../composables/useRunTracker'
import { formatDuration, statusLabel, statusTone } from '../utils/status'

/**
 * 流水线工作台（重构版）：
 *
 * 修复要点：
 * 1. 运行详情与会话解耦——点击历史不覆盖、不消费；运行中可在列表间来回切换；
 * 2. 日志走增量接口（offset 续传）并追加渲染，不再每秒全量重绘；
 * 3. sha 输入自动清洗 + 禁用原因可读；
 * 4. 历史补齐字段/筛选/键盘可达；重跑常显；
 * 5. 状态中文化 + 统一设计令牌（无硬编码 Element 默认色）；
 * 6. 粘性运行条（状态、耗时、取消、重跑、复制）随页面滚动始终可见；
 * 7. 左右分栏可拖拽；空状态带引导动作。
 */
const route = useRoute()
const router = useRouter()

const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)

/** 环境策略：选环境即套用（流水线 + 部署目标），仍可手动覆盖。 */
interface EnvStrategy {
  key: string
  label: string
  pipeline: string
  target: string
  desc: string
}

const ENV_STRATEGIES: EnvStrategy[] = [
  { key: 'dev', label: '开发环境', pipeline: 'verify', target: 'host', desc: '只构建+测试，不部署（本机直跑）' },
  { key: 'test', label: '测试环境', pipeline: 'release', target: 'docker', desc: '构建+镜像+部署到本机 Docker' },
  { key: 'prod', label: '生产环境', pipeline: 'release', target: 'server', desc: '构建+部署到远程服务器' },
]

const strategy = computed<EnvStrategy>(() =>
  ENV_STRATEGIES.find((s) => s.key === env.value) ?? ENV_STRATEGIES[1])

/** 流水线的人类可读名称：release / verify 不再直接暴露给用户。 */
function pipelineLabel(id: string): string {
  if (id === 'release') return '发布'
  if (id === 'verify') return '检查构建'
  return id
}

function pipelineDesc(id: string): string {
  if (id === 'release') return '构建 → 测试 → 镜像 → 部署 → 健康检查'
  if (id === 'verify') return '只构建 + 测试，不部署（PR 检查）'
  return '仓库自定义流水线'
}

/** Header 副标题：反映"当前真正会怎么跑"，而不是只显示环境默认。 */
const headerSubtitle = computed(() => {
  const target = deployTargets.value.find((t) => t.target === deployTarget.value)
  return `${strategy.value.label} · ${pipelineLabel(pipelineId.value)} → ${target?.label ?? deployTarget.value}`
})

const projects = ref<ProjectRow[]>([])
/** 项目下拉选项：接口为空时至少保留当前项目，避免出现"空下拉框"。 */
const projectChoices = computed<Array<{ id: string, name: string }>>(() =>
  projects.value.length
    ? projects.value.map((p) => ({ id: p.id, name: p.name }))
    : [{ id: projectId.value, name: projectId.value }])
const commits = ref<GitCommit[]>([])
const commitsLoading = ref(false)

const pipelines = ref<string[]>([])
const engine = ref('')
const pipelineId = ref('')
const sha = ref('')
const triggerBusy = ref(false)
const pipelinesLoading = ref(false)
/** 部署目标（方案 C：平台部署器，运行时可覆盖 pipeline.yml 的 deploy.target）。 */
const deployTarget = ref('docker')
const deployTargets = ref<DeployTargetOption[]>([
  { target: 'docker', label: '本机 Docker 容器', available: true, detail: '' },
  { target: 'host', label: '本机直接运行', available: true, detail: '' },
  { target: 'server', label: '远程服务器', available: false, detail: '未配置' },
])

const history = ref<HistoryItem[]>([])
const historyLoading = ref(false)

const actions = ref<ActionView[]>([])
const actionsLoading = ref(false)
const actionBusy = ref('')
const actionResult = shallowRef<ActionRunView | null>(null)

const leftPct = ref(34)
const topError = ref('')

const {
  run, runId, loading: runLoading, errorText, startedAt, running,
  open: openRun, attach, cancel, clear,
} = useRunTracker({ onFinished: () => void loadHistory() })

// L3 确认-执行闭环
const changeDialog = ref(false)
const change = ref<ChangeView | null>(null)
const changePassword = ref('')
const confirmToken = ref<string | null>(null)
const changeBusy = ref(false)

const lastGoodSha = computed(() =>
  history.value.find((h) => h.status === 'SUCCEEDED')?.gitSha ?? '')

/* ---------------------------------------------------------------- 数据加载 */

async function loadPipelines(): Promise<void> {
  pipelinesLoading.value = true
  try {
    const data = await listPipelines(projectId.value)
    engine.value = data.engine
    pipelines.value = data.pipelines ?? []
    if (!pipelines.value.includes(pipelineId.value)) {
      // 优先用"环境策略"推荐的流水线（开发=检查构建，测试/生产=发布）
      const preferred = strategy.value.pipeline
      pipelineId.value = pipelines.value.includes(preferred)
        ? preferred
        : pipelines.value[0] ?? ''
    }
  } catch (e) {
    topError.value = `加载流水线失败：${e instanceof Error ? e.message : String(e)}`
    pipelines.value = []
    pipelineId.value = ''
  } finally {
    pipelinesLoading.value = false
  }
}

async function loadHistory(): Promise<void> {
  historyLoading.value = true
  try {
    history.value = await getHistory(projectId.value)
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

async function loadActions(): Promise<void> {
  actionsLoading.value = true
  try {
    actions.value = await listActions(projectId.value, env.value)
  } catch {
    actions.value = []
  } finally {
    actionsLoading.value = false
  }
}

async function loadDeployTargets(): Promise<void> {
  try {
    const list = await listDeployTargets()
    if (list.length) {
      deployTargets.value = list
      const preferred = list.find((t) => t.target === strategy.value.target && t.available)
      const current = list.find((t) => t.target === deployTarget.value && t.available)
      deployTarget.value = (preferred ?? current ?? list.find((t) => t.available))?.target ?? 'docker'
    }
  } catch {
    // 端点不可用时保留本地默认（docker）
  }
}

/** 项目列表（Header 选择器）；当前项目不在列表时保留全局值。 */
async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
    if (!projects.value.some((p) => p.id === projectId.value) && projects.value.length) {
      appStore.projectId = projects.value[0].id
    }
  } catch {
    projects.value = []
  }
}

/** 提交列表：用 git 选提交，而不是让用户手敲 sha。 */
async function loadCommits(): Promise<void> {
  commitsLoading.value = true
  try {
    const page = await gitCommits(projectId.value, 30)
    commits.value = page.commits
    // 当前 sha 不在最近提交里（或为空）时，默认选最新一条
    if (page.commits.length && !page.commits.some((c) => c.sha === sha.value)) {
      sha.value = page.commits[0].sha
    }
  } catch {
    commits.value = []
  } finally {
    commitsLoading.value = false
  }
}

/** 切换环境：套用该环境的流水线 + 部署目标策略。 */
function applyEnv(key: string): void {
  if (!ENV_STRATEGIES.some((s) => s.key === key)) return
  appStore.env = key
  const s = ENV_STRATEGIES.find((x) => x.key === key)!
  if (pipelines.value.includes(s.pipeline)) {
    pipelineId.value = s.pipeline
  }
  const target = deployTargets.value.find((t) => t.target === s.target)
  if (!target || target.available) {
    deployTarget.value = s.target
  }
}

function applyProject(id: string): void {
  appStore.projectId = id
  sha.value = ''
  // 项目写进 URL：刷新/分享链接不会掉回默认项目
  void router.replace({ query: { ...route.query, projectId: id } })
}

watch([projectId, env], () => {
  void loadPipelines()
  void loadActions()
  void loadHistory()
  void loadDeployTargets()
  void loadCommits()
})

onMounted(async () => {
  // ?projectId= 优先：从项目页/书签/分享链接进来时按 URL 定位项目
  const fromQuery = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  if (fromQuery) {
    appStore.projectId = fromQuery
  }
  await loadProjects()
  await Promise.all([loadPipelines(), loadActions(), loadHistory(), loadDeployTargets(), loadCommits()])
  const fromUrl = typeof route.query.runId === 'string' ? route.query.runId : ''
  if (fromUrl) void openRun(fromUrl)
})

/* ---------------------------------------------------------------- 触发 / 查阅 */

async function doTrigger(): Promise<void> {
  if (!pipelineId.value || !/^[0-9a-f]{7,40}$/.test(sha.value) || triggerBusy.value) return
  triggerBusy.value = true
  topError.value = ''
  try {
    const result = await triggerPipeline(
      pipelineId.value, projectId.value, sha.value, deployTarget.value)
    const initial: RunView = {
      runId: result.runId,
      status: result.status,
      stages: [],
      artifacts: [],
      log: '',
    }
    attach(result.runId, initial)
    syncUrl(result.runId)
    ElMessage.success('已触发运行')
    void loadHistory()
  } catch (e) {
    topError.value = `触发流水线失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    triggerBusy.value = false
  }
}

function openedRun(item: HistoryItem): void {
  void openRun(item.runId, { startedAt: item.createdAt })
  sha.value = item.gitSha.slice(0, 8)
  pipelineId.value = item.pipelineId
  syncUrl(item.runId)
}

async function rerun(item: HistoryItem): Promise<void> {
  sha.value = item.gitSha.slice(0, 8)
  pipelineId.value = item.pipelineId
  await doTrigger()
}

async function doCancel(): Promise<void> {
  if (await cancel()) ElMessage.warning('已请求取消')
}

function closeRun(): void {
  clear()
  syncUrl('')
}

/** 运行 id 同步到 URL，刷新/分享可恢复（替代"运行状态只活在内存里"）。 */
function syncUrl(id: string): void {
  const query = { ...route.query }
  if (id) query.runId = id
  else delete query.runId
  void router.replace({ query })
}

/* ---------------------------------------------------------------- 动作 */

async function runActionNow(action: string): Promise<void> {
  actionBusy.value = action
  actionResult.value = null
  try {
    const plan = await getActionPlan(projectId.value, action)
    const commands = plan.commands.length ? plan.commands.join('\n') : '（无命令）'
    const ok = await ElMessageBox.confirm(
      `将执行：\n${commands}`,
      '执行确认',
      { confirmButtonText: '执行', cancelButtonText: '取消', type: 'warning' },
    ).then(() => true).catch(() => false)
    if (!ok) return
    const result = await runAction(projectId.value, env.value, action)
    actionResult.value = result
    ElMessage.success(`${action} 执行完毕：${statusLabel(result.status)}`)
  } catch (e) {
    ElMessage.error(`执行失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    actionBusy.value = ''
  }
}

async function requestActionChange(action: string): Promise<void> {
  actionBusy.value = action
  try {
    const result = await requestAction(projectId.value, env.value, action)
    change.value = await getChange(result.changeId)
    changePassword.value = ''
    confirmToken.value = null
    changeDialog.value = true
  } catch (e) {
    ElMessage.error(`登记失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    actionBusy.value = ''
  }
}

async function doConfirmChange(): Promise<void> {
  if (!change.value || confirmToken.value) return
  changeBusy.value = true
  try {
    const result = await confirmChange(
      change.value.changeId,
      change.value.highRisk ? changePassword.value : undefined,
    )
    confirmToken.value = result.confirmToken
    ElMessage.success('已确认，令牌 5 分钟内有效')
  } catch (e) {
    ElMessage.error(`确认失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    changeBusy.value = false
  }
}

async function doApplyChange(): Promise<void> {
  if (!change.value || !confirmToken.value) return
  changeBusy.value = true
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    ElMessage.success(`变更执行完毕：${statusLabel(result.status)}`)
    changeDialog.value = false
    void loadHistory()
  } catch (e) {
    ElMessage.error(`执行失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    changeBusy.value = false
  }
}

/* ---------------------------------------------------------------- 生命周期 */

/** 运行计时：运行中每秒刷新（否则 "运行中 3.2s" 会定格）。 */
const nowTick = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null

watch(running, (value) => {
  if (value && tickTimer === null) {
    tickTimer = setInterval(() => (nowTick.value = Date.now()), 1000)
  } else if (!value && tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}, { immediate: true })

onBeforeUnmount(() => {
  if (tickTimer !== null) clearInterval(tickTimer)
})

const runDuration = computed(() => {
  if (!run.value) return ''
  if (!running.value) {
    return formatDuration(run.value.stages.reduce((sum, s) => sum + s.durationMs, 0))
  }
  // 依赖 nowTick 触发重算
  return formatDuration(Math.max(0, nowTick.value - startedAt.value))
})
</script>

<template>
  <div class="pipeline-page">
    <PageHeader title="流水线工作台" :subtitle="headerSubtitle">
      <template #actions>
        <span v-if="running" class="run-chip busy">
          <span class="dot" /> 运行中 · {{ runDuration }}
        </span>
        <span v-else-if="run" class="run-chip" :class="statusTone(run.status)">
          <span class="dot" /> {{ statusLabel(run.status) }}
        </span>
      </template>
    </PageHeader>

    <div class="wp-body">
      <!-- 左：触发 + 历史 + 动作 -->
      <div class="wp-left" :style="{ width: `${leftPct}%` }">
        <div class="wp-left-inner">
          <!-- 上下文：项目 + 环境（环境决定默认策略，可再手动覆盖） -->
          <div class="ctx-bar">
            <label class="ctx-field">
              <span class="ctx-label">项目</span>
              <el-select
                :model-value="projectId" size="default" class="ctx-select"
                placeholder="选择项目" filterable
                @update:model-value="applyProject"
              >
                <el-option
                  v-for="p in projectChoices" :key="p.id"
                  :label="`${p.name}（${p.id}）`" :value="p.id"
                />
              </el-select>
            </label>
            <label class="ctx-field">
              <span class="ctx-label">环境</span>
              <el-select
                :model-value="env" size="default" class="ctx-select env"
                @update:model-value="applyEnv"
              >
                <el-option
                  v-for="s in ENV_STRATEGIES" :key="s.key"
                  :label="s.label" :value="s.key"
                >
                  <span class="ctx-opt-main">{{ s.label }}</span>
                  <span class="ctx-opt-desc">{{ s.pipeline === 'verify' ? '只构建+测试' : '构建+部署' }} · {{ s.desc }}</span>
                </el-option>
              </el-select>
            </label>
          </div>

          <TriggerBar
            v-model:pipeline-id="pipelineId"
            v-model:sha="sha"
            v-model:deploy-target="deployTarget"
            :pipelines="pipelines"
            :engine="engine"
            :busy="triggerBusy || pipelinesLoading"
            :last-good-sha="lastGoodSha"
            :deploy-targets="deployTargets"
            :commits="commits"
            :commits-loading="commitsLoading"
            @run="doTrigger"
            @refresh-commits="loadCommits"
          />

          <HistoryList
            class="fill"
            :items="history"
            :active-run-id="runId"
            :loading="historyLoading"
            @open="openedRun"
            @rerun="rerun"
            @refresh="loadHistory"
          />

          <ActionPanel
            :actions="actions"
            :busy-action="actionBusy"
            :loading="actionsLoading"
            @run="runActionNow"
            @request="requestActionChange"
          />

          <!-- 动作执行结果（状态 + 日志尾部），不再只在 toast 里一闪而过 -->
          <section v-if="actionResult" class="action-result">
            <div class="ar-head">
              <span class="ar-title mono">{{ actionResult.action }}</span>
              <span class="ar-status" :class="statusTone(actionResult.status)">
                {{ statusLabel(actionResult.status) }}
              </span>
              <span class="ar-meta mono">
                exit={{ actionResult.exitCode }} · {{ formatDuration(actionResult.durationMs) }}
              </span>
              <button type="button" class="ar-close" title="关闭" @click="actionResult = null">
                <el-icon><Close /></el-icon>
              </button>
            </div>
            <pre v-if="actionResult.logTail" class="ar-log mono">{{ actionResult.logTail }}</pre>
            <p v-else class="ar-meta">（无输出）</p>
          </section>
        </div>
      </div>

      <ResizableSplitter v-model="leftPct" :min="22" :max="55" />

      <!-- 右：运行详情 -->
      <div class="wp-right">
        <el-alert
          v-if="topError" class="top-alert" type="error" :closable="true" show-icon
          :title="topError" @close="topError = ''"
        />

        <div v-if="!run && !runLoading" class="run-empty">
          <div class="re-illus" aria-hidden="true">
            <svg width="88" height="60" viewBox="0 0 88 60" fill="none">
              <rect x="6" y="8" width="76" height="44" rx="6" fill="var(--surface-sunken)" />
              <rect x="14" y="18" width="22" height="4" rx="2" fill="var(--accent)" opacity="0.5" />
              <rect x="42" y="18" width="22" height="4" rx="2" fill="var(--ink-faint)" opacity="0.35" />
              <rect x="14" y="28" width="50" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.25" />
              <rect x="14" y="36" width="30" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.2" />
            </svg>
          </div>
          <p class="re-title">还没有选中运行</p>
          <p class="re-sub">
            {{ pipelines.length
              ? '在上方填入提交 sha 触发一次，或从左侧历史点开某次运行'
              : '该仓库还没提交 ci/pipeline.yml——生成并提交后再回来运行' }}
          </p>
          <button
            type="button" class="re-btn"
            :disabled="!pipelines.length || (!lastGoodSha && !sha)"
            @click="sha = sha || lastGoodSha.slice(0, 8); doTrigger()"
          >
            <el-icon><VideoPlay /></el-icon> 运行最近提交
          </button>
        </div>

        <template v-else>
          <!-- 粘性运行条 -->
          <div class="run-bar" :class="statusTone(run?.status ?? '')">
            <span class="rb-status">
              <span class="rb-dot" /> {{ run ? statusLabel(run.status) : '加载中' }}
            </span>
            <span class="rb-id mono" :title="runId">{{ runId.slice(0, 12) }}</span>
            <span v-if="run" class="rb-meta">{{ run.stages.length }} 阶段 · {{ run.artifacts.length }} 产物 · {{ runDuration }}</span>
            <span v-if="errorText" class="rb-err">{{ errorText }}</span>
            <div class="rb-actions">
              <button v-if="running" type="button" class="rb-btn warn" @click="doCancel">取消运行</button>
              <button type="button" class="rb-btn" :disabled="running || !sha" @click="doTrigger">
                <el-icon><RefreshRight /></el-icon> 重跑
              </button>
              <button type="button" class="rb-btn icon" title="关闭详情" @click="closeRun">
                <el-icon><Close /></el-icon>
              </button>
            </div>
          </div>

          <div class="run-detail">
            <StageTimeline
              v-if="run"
              :stages="run.stages"
              :running="running"
              :started-at="startedAt"
            />

            <section v-if="run?.artifacts.length" class="artifact-panel">
              <h3 class="ap-title">产物</h3>
              <div
                v-for="(a, i) in run.artifacts" :key="`${a.kind}-${a.ref}-${i}`"
                class="artifact-row"
              >
                <span class="art-kind">{{ a.kind }}</span>
                <code class="art-ref mono" :title="a.ref">{{ a.ref }}</code>
                <code class="art-digest mono" :title="a.digest">{{ a.digest ? `${a.digest.slice(0, 12)}…` : '—' }}</code>
                <span class="art-size">{{ a.sizeBytes }} B</span>
              </div>
            </section>

            <RunLog
              v-if="run"
              :run-id="run.runId"
              :running="running"
              class="grow"
            />
          </div>
        </template>
      </div>
    </div>

    <!-- L3 变更确认（合并为一次弹窗流程，语义明确） -->
    <el-dialog v-model="changeDialog" title="L3 变更确认" width="560px">
      <div v-if="change" class="confirm-card">
        <div class="line"><span>变更单</span><b class="mono">{{ change.changeId }}</b></div>
        <div class="line"><span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b></div>
        <div class="line"><span>类型</span><b>{{ change.kind }}</b></div>
        <div class="line"><span>内容</span><b>{{ change.summary }}</b></div>
        <div class="line"><span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b></div>
        <div class="line">
          <span>风险</span>
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small" effect="light" round>
            {{ change.highRisk ? '高危（需二次密码）' : '常规' }}
          </el-tag>
        </div>
        <el-input
          v-if="change.highRisk && !confirmToken"
          v-model="changePassword" type="password"
          placeholder="账号体系接入前无法通过，仅验证流程连通" show-password
        />
        <el-alert
          v-if="confirmToken" type="success" :closable="false" show-icon
          title="确认令牌已签发（5 分钟有效、一次性、绑定当前配方）"
          :description="confirmToken"
        />
      </div>
      <template #footer>
        <el-button @click="changeDialog = false">关闭</el-button>
        <el-button v-if="!confirmToken" :loading="changeBusy" @click="doConfirmChange">
          1. 确认变更
        </el-button>
        <el-button v-else type="primary" :loading="changeBusy" @click="doApplyChange">
          2. 执行变更
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pipeline-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}
.wp-body {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}
.wp-left {
  flex: none;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  border-right: 1px solid var(--line-soft);
}
.wp-left-inner {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  padding: 12px 12px 12px 14px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.wp-left-inner .fill {
  flex: 1 1 260px;
  min-height: 200px;
}
.wp-right {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
}
.top-alert {
  margin: 12px 14px 0;
}
.grow {
  flex: 1 1 240px;
  min-height: 200px;
}

/* 运行条 */
.run-bar {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--line-soft);
}
.rb-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.rb-dot {
  width: 7px;
  height: 7px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.run-bar.ok .rb-dot { background: var(--ok); }
.run-bar.bad .rb-dot { background: var(--danger); }
.run-bar.busy .rb-dot {
  background: var(--accent);
  animation: dot-pulse 1.4s ease-in-out infinite;
}
@keyframes dot-pulse { 50% { opacity: 0.3; } }
.rb-id {
  padding: 1px 7px;
  font-size: 11px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.rb-meta {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.rb-err {
  font-size: 11.5px;
  color: var(--danger);
}
.rb-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.rb-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.rb-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.rb-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.rb-btn.warn {
  color: var(--warn);
  background: var(--warn-soft);
  border-color: color-mix(in srgb, var(--warn) 30%, transparent);
}
.rb-btn.icon {
  width: 26px;
  padding: 0;
  justify-content: center;
}

.run-detail {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  padding: 12px 14px 14px;
  overflow: hidden;
}

/* 产物 */
.artifact-panel {
  flex: none;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ap-title {
  margin: 0 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.artifact-row {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr) 150px 76px;
  gap: 10px;
  padding: 5px 0;
  font-size: 11.5px;
  border-top: 1px solid var(--line-soft);
}
.art-kind {
  color: var(--accent-ink);
}
.art-ref,
.art-digest {
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.art-digest {
  color: var(--ink-muted);
}
.art-size {
  color: var(--ink-faint);
  text-align: right;
}

/* 动作执行结果 */
.action-result {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ar-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ar-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.ar-status {
  padding: 1px 7px;
  font-size: 10.5px;
  border-radius: var(--radius-pill);
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.ar-status.ok {
  color: var(--ok);
  background: var(--ok-soft);
}
.ar-status.bad {
  color: var(--danger);
  background: var(--danger-soft);
}
.ar-meta {
  margin: 0;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ar-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.ar-close:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.ar-log {
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--code-bg);
  border-radius: var(--radius-xs);
  max-height: 160px;
}

/* 空状态 */
.run-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 40px;
  text-align: center;
}
.re-title {
  margin: 10px 0 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-muted);
}
.re-sub {
  margin: 0;
  font-size: 12px;
  color: var(--ink-faint);
}
.re-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 14px;
  margin-top: 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-sm);
}
.re-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

/* 上下文选择器（项目 / 环境） */
.ctx-bar {
  display: flex;
  flex: none;
  gap: 10px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ctx-field {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.ctx-label {
  font-size: 10.5px;
  color: var(--ink-faint);
  letter-spacing: 0.02em;
}
.ctx-select {
  width: 100%;
}
.ctx-opt-main {
  margin-right: 8px;
}
.ctx-opt-desc {
  font-size: 11px;
  color: var(--ink-faint);
}

/* Header 状态 chip */
.run-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  font-size: 11.5px;
  color: var(--ink-muted);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.run-chip .dot {
  width: 5px;
  height: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.run-chip.ok .dot { background: var(--ok); }
.run-chip.bad .dot { background: var(--danger); }
.run-chip.busy .dot {
  background: var(--accent);
  animation: dot-pulse 1.4s ease-in-out infinite;
}

/* 确认卡 */
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.confirm-card .line {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.confirm-card .line span {
  flex: none;
  width: 90px;
  color: var(--ink-muted);
}

@media (max-width: 900px) {
  .wp-body {
    flex-direction: column;
  }
  .wp-left {
    width: 100% !important;
    height: auto;
    max-height: 45%;
    border-right: 0;
    border-bottom: 1px solid var(--line-soft);
  }
}
</style>
