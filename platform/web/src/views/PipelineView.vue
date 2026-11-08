<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CopyDocument, CaretBottom, Loading, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getActionPlan,
  listActions,
  requestAction,
  runAction,
  type ActionRunView,
  type ActionView
} from '../api/action'
import { applyChange, confirmChange, getChange } from '../api/change'
import type { ChangeView, ConfirmResponse } from '../types'
import PageHeader from '../layouts/components/PageHeader.vue'
import {
  cancelRun,
  getHistory,
  getRun,
  isTerminalStatus,
  listPipelines,
  triggerPipeline,
  type ArtifactView,
  type HistoryItem,
  type RunView,
  type StageView
} from '../api/pipeline'

const projectId = ref('RY')
const env = ref('test')

const pipelineList = ref<string[]>([])
const engine = ref('')
const selectedPipeline = ref('')
const gitSha = ref('')
const pipelinesLoading = ref(false)

const actions = ref<ActionView[]>([])
const actionsLoading = ref(false)
const planCache = ref<Record<string, ActionView>>({})
const actionBusy = ref('')
const actionResult = ref<ActionRunView | null>(null)
const requestInfo = ref('')

const run = ref<RunView | null>(null)
const runId = ref('')
const runBusy = ref(false)
const errorText = ref('')
const pollCount = ref(0)
let pollTimer: ReturnType<typeof setInterval> | null = null

const history = ref<HistoryItem[]>([])
const historyLoading = ref(false)

// L3 确认-执行闭环
const changeDialog = ref(false)
const change = ref<ChangeView | null>(null)
const changePassword = ref('')
const confirmToken = ref<string | null>(null)
const changeBusy = ref(false)

// 日志面板
const followLog = ref(true)
const logRef = ref<HTMLElement | null>(null)

const POLL_INTERVAL_MS = 1000

const polling = computed(() => pollTimer !== null)
const canTrigger = computed(() => !!selectedPipeline.value && /^[0-9a-f]{7,40}$/.test(gitSha.value))
const runActive = computed(() => !!run.value && !isTerminalStatus(run.value.status))
const logLineCount = computed(() => (run.value?.log ?? '').split('\n').filter(l => l.trim()).length)

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function shortDigest(digest: string): string {
  const value = digest || '—'
  if (value.length <= 20) return value
  return `${value.slice(0, 16)}…${value.slice(-6)}`
}

type Tone = 'ok' | 'bad' | 'busy'

function stageTone(status: string): Tone {
  if (status === 'SUCCEEDED') return 'ok'
  if (['FAILED', 'TIMEOUT', 'CANCELLED'].includes(status)) return 'bad'
  return 'busy'
}

function elTagType(tone: Tone): 'success' | 'danger' | 'primary' {
  return tone === 'ok' ? 'success' : tone === 'bad' ? 'danger' : 'primary'
}

function artifactKey(a: ArtifactView, i: number): string {
  return `${a.kind}:${a.ref}:${a.digest}:${i}`
}

function stageKey(s: StageView, i: number): string {
  return `${s.id}:${i}`
}

function formatTime(ms: number): string {
  if (!ms) return '—'
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function shortSha(sha: string): string {
  return sha.length > 12 ? sha.slice(0, 12) : sha
}

async function loadPipelines() {
  pipelinesLoading.value = true
  errorText.value = ''
  try {
    const data = await listPipelines(projectId.value)
    engine.value = data.engine
    pipelineList.value = data.pipelines ?? []
    if (!pipelineList.value.includes(selectedPipeline.value)) {
      selectedPipeline.value = pipelineList.value[0] ?? ''
    }
  } catch (error) {
    errorText.value = `加载流水线失败：${messageOf(error)}`
    pipelineList.value = []
    selectedPipeline.value = ''
  } finally {
    pipelinesLoading.value = false
  }
}

async function loadActions() {
  actionsLoading.value = true
  errorText.value = ''
  planCache.value = {}
  try {
    actions.value = await listActions(projectId.value, env.value)
  } catch (error) {
    errorText.value = `加载动作失败：${messageOf(error)}`
    actions.value = []
  } finally {
    actionsLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await getHistory(projectId.value)
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

async function openHistory(item: HistoryItem) {
  stopPolling()
  errorText.value = ''
  try {
    run.value = await getRun(item.runId)
    runId.value = item.runId
    if (!isTerminalStatus(run.value.status)) startPolling(item.runId)
  } catch (error) {
    ElMessage.error(`加载运行失败：${messageOf(error)}`)
  }
}

async function showPlan(action: string) {
  if (planCache.value[action]) return
  actionBusy.value = action
  try {
    planCache.value[action] = await getActionPlan(projectId.value, action)
  } catch (error) {
    ElMessage.error(`加载计划失败：${messageOf(error)}`)
  } finally {
    actionBusy.value = ''
  }
}

async function doRunAction(action: string) {
  actionBusy.value = action
  actionResult.value = null
  requestInfo.value = ''
  try {
    actionResult.value = await runAction(projectId.value, env.value, action)
    ElMessage.success(`${action} 执行完毕：${actionResult.value.status}`)
  } catch (error) {
    ElMessage.error(`执行失败：${messageOf(error)}`)
  } finally {
    actionBusy.value = ''
  }
}

async function doRequestAction(action: string) {
  actionBusy.value = action
  actionResult.value = null
  requestInfo.value = ''
  confirmToken.value = null
  changePassword.value = ''
  try {
    const result = await requestAction(projectId.value, env.value, action)
    requestInfo.value = `${result.changeId} · planHash ${result.planHash.slice(0, 24)}…`
    change.value = await getChange(result.changeId)
    changeDialog.value = true
  } catch (error) {
    ElMessage.error(`登记失败：${messageOf(error)}`)
  } finally {
    actionBusy.value = ''
  }
}

async function doConfirmChange() {
  if (!change.value || confirmToken.value) return
  changeBusy.value = true
  try {
    const result: ConfirmResponse = await confirmChange(
      change.value.changeId,
      change.value.highRisk ? changePassword.value : undefined
    )
    confirmToken.value = result.confirmToken
    ElMessage.success('已确认，令牌 5 分钟内有效')
  } catch (error) {
    ElMessage.error(`确认失败：${messageOf(error)}`)
  } finally {
    changeBusy.value = false
  }
}

async function doApplyChange() {
  if (!change.value || !confirmToken.value) return
  changeBusy.value = true
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    run.value = {
      runId: `${change.value.changeId}（动作 ${result.action}）`,
      status: result.status,
      stages: [],
      artifacts: result.artifacts,
      log: result.logTail
    }
    runId.value = ''
    ElMessage.success(`变更执行完毕：${result.status}`)
    changeDialog.value = false
    void loadHistory()
  } catch (error) {
    ElMessage.error(`执行失败：${messageOf(error)}`)
  } finally {
    changeBusy.value = false
  }
}

async function doCancel() {
  if (!run.value || !runActive.value) return
  try {
    run.value = await cancelRun(run.value.runId)
    if (isTerminalStatus(run.value.status)) stopPolling()
    ElMessage.warning('已请求取消')
    void loadHistory()
  } catch (error) {
    ElMessage.error(`取消失败：${messageOf(error)}`)
  }
}

async function copyLog() {
  const text = run.value?.log ?? ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('日志已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function clearRunView() {
  run.value = null
  runId.value = ''
  actionResult.value = null
  requestInfo.value = ''
  pollCount.value = 0
}

function startPolling(id: string) {
  stopPolling()
  pollCount.value = 0
  pollTimer = setInterval(async () => {
    pollCount.value += 1
    try {
      const data = await getRun(id)
      run.value = data
      errorText.value = ''
      if (isTerminalStatus(data.status)) {
        stopPolling()
        void loadHistory()
      }
    } catch (error) {
      errorText.value = `轮询运行状态失败：${messageOf(error)}`
      stopPolling()
    }
  }, POLL_INTERVAL_MS)
}

async function doTrigger() {
  if (!canTrigger.value || runBusy.value) return
  runBusy.value = true
  errorText.value = ''
  stopPolling()
  clearRunView()
  try {
    const result = await triggerPipeline(selectedPipeline.value, projectId.value, gitSha.value)
    runId.value = result.runId
    run.value = await getRun(result.runId)
    if (!isTerminalStatus(run.value.status)) startPolling(result.runId)
    void loadHistory()
  } catch (error) {
    errorText.value = `触发流水线失败：${messageOf(error)}`
  } finally {
    runBusy.value = false
  }
}

watch(
  () => run.value?.log,
  async () => {
    if (!followLog.value) return
    await nextTick()
    const el = logRef.value
    if (el) el.scrollTop = el.scrollHeight
  }
)

watch([projectId, env], () => {
  void loadPipelines()
  void loadActions()
  void loadHistory()
})

onMounted(() => {
  void loadPipelines()
  void loadActions()
  void loadHistory()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="pipeline-page">
    <PageHeader title="流水线工作台" :subtitle="`引擎 ${engine || '—'} · ${projectId}/${env}`">
      <template #actions>
        <span v-if="polling" class="polling-chip">
          <el-icon class="is-loading"><Loading /></el-icon>
          轮询中 · {{ pollCount }}
        </span>
        <el-button
          v-if="runActive"
          type="warning"
          plain
          size="small"
          @click="doCancel"
        >
          取消运行
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="pipelinesLoading" @click="loadPipelines">
          刷新
        </el-button>
      </template>
    </PageHeader>

    <!-- 页面唯一滚动容器，外层 app-shell 永不滚动 -->
    <div class="page-scroll">
      <p class="page-lead">
        命令由人写在仓库配方里；平台只做解析、隔离与审批。运行结果每 1s 刷新至终态。
      </p>

      <section class="trigger-card" aria-label="触发流水线">
        <div class="section-heading">
          <span class="step">01</span>
          <h2 class="section-title">
            触发运行
          </h2>
        </div>
      <div class="trigger-form">
        <div class="tf-field">
          <label>项目</label>
          <el-select v-model="projectId" size="default" style="width: 100%">
            <el-option label="RY · RuoYi-Vue-Plus" value="RY" />
          </el-select>
        </div>
        <div class="tf-field">
          <label>环境</label>
          <el-select v-model="env" size="default" style="width: 100%">
            <el-option label="dev" value="dev" />
            <el-option label="test" value="test" />
            <el-option label="prod" value="prod" />
          </el-select>
        </div>
        <div class="tf-field">
          <label>流水线</label>
          <el-select v-model="selectedPipeline" size="default" :loading="pipelinesLoading" style="width: 100%">
            <el-option v-for="p in pipelineList" :key="p" :label="p" :value="p" />
          </el-select>
        </div>
        <div class="tf-field grow">
          <label>gitSha（7-40 位小写十六进制）</label>
          <el-input v-model="gitSha" class="mono" size="default" placeholder="a1b2c3d" clearable />
        </div>
        <el-button
          type="primary"
          :icon="VideoPlay"
          :loading="runBusy"
          :disabled="!canTrigger"
          @click="doTrigger"
        >
          触发
        </el-button>
      </div>
    </section>

    <el-alert v-if="errorText" class="block-alert" type="error" :closable="false" :title="errorText" show-icon />

    <div class="workbench-grid">
      <div class="left-col">
        <section class="panel" aria-label="动作列表">
          <div class="panel-heading">
            <span class="step">02</span>
            <h2 class="section-title">
              动作
              <span class="panel-count">{{ actions.length }} 项 · {{ projectId }}/{{ env }}</span>
            </h2>
          </div>
          <div v-loading="actionsLoading" class="panel-body">
            <div v-for="a in actions" :key="a.action" class="action-card">
              <div class="action-head">
                <b class="mono action-name">{{ a.action }}</b>
                <el-tag size="small" :type="a.level.startsWith('L3') ? 'warning' : 'info'" effect="light" round>
                  {{ a.level.replace('_EXECUTE_ISOLATED', '').replace('_EXECUTE_PROD', '') }}
                </el-tag>
                <el-tag v-if="a.humanAuthored" size="small" type="success" effect="light" round>
                  人写配方
                </el-tag>
              </div>
              <div class="kv">
                <span class="kv-label">来源</span>
                <span class="mono kv-value" :title="a.source">{{ a.source }}</span>
              </div>
              <div class="kv">
                <span class="kv-label">产物</span>
                <span class="mono kv-value">{{ a.produce.join(', ') || '—' }}</span>
              </div>
              <div class="kv">
                <span class="kv-label">超时</span>
                <span class="mono kv-value">{{ a.timeoutMinutes }} 分钟</span>
              </div>

              <details class="cmd-fold" :open="a.commands.length > 0">
                <summary>命令原文</summary>
                <pre class="commands mono">{{ a.commands.length ? a.commands.join('\n') : (planCache[a.action]?.commands.join('\n') ?? '（列表未返回命令，点「查看计划」读取）') }}</pre>
              </details>

              <div class="action-buttons">
                <el-button size="small" :loading="actionBusy === a.action" @click="showPlan(a.action)">
                  查看计划
                </el-button>
                <el-button
                  v-if="!a.level.startsWith('L3')"
                  size="small"
                  type="primary"
                  plain
                  :loading="actionBusy === a.action"
                  @click="doRunAction(a.action)"
                >
                  执行
                </el-button>
                <el-button
                  v-else
                  size="small"
                  type="warning"
                  plain
                  :loading="actionBusy === a.action"
                  @click="doRequestAction(a.action)"
                >
                  登记变更
                </el-button>
              </div>
            </div>
            <el-empty v-if="!actionsLoading && !actions.length" description="暂无动作" :image-size="60" />
          </div>
        </section>

        <section class="panel" aria-label="构建历史">
          <div class="panel-heading">
            <span class="step">03</span>
            <h2 class="section-title">
              构建历史
              <span class="panel-count">{{ history.length }} 条 · 最新在前</span>
            </h2>
            <el-button size="small" text :icon="Refresh" :loading="historyLoading" @click="loadHistory" />
          </div>
          <div v-loading="historyLoading" class="panel-body history-body">
            <button
              v-for="h in history"
              :key="h.runId"
              type="button"
              class="history-item"
              :class="{ active: h.runId === runId }"
              :aria-pressed="h.runId === runId"
              @click="openHistory(h)"
            >
              <span class="hist-dot" :class="stageTone(h.status)" />
              <span class="hist-pipeline">{{ h.pipelineId }}</span>
              <span class="mono hist-sha" :title="h.gitSha">{{ shortSha(h.gitSha) }}</span>
              <span class="hist-status" :class="stageTone(h.status)">{{ h.status }}</span>
              <time class="hist-time mono">{{ formatTime(h.createdAt) }}</time>
            </button>
            <el-empty v-if="!historyLoading && !history.length" description="暂无构建历史" :image-size="60" />
          </div>
        </section>
      </div>

      <section class="panel run-panel" aria-label="运行详情">
        <div class="panel-heading">
          <span class="step">04</span>
          <h2 class="section-title">
            运行详情
            <span v-if="runId" class="mono panel-count">{{ runId }}</span>
          </h2>
          <span v-if="polling" class="polling-chip">
            <el-icon class="is-loading"><Loading /></el-icon>
            轮询中 · {{ pollCount }}
          </span>
          <el-checkbox v-model="followLog" size="small" class="follow">
            <el-icon><CaretBottom /></el-icon>
            跟随
          </el-checkbox>
        </div>
        <div class="panel-body">
          <div v-if="!run" class="empty-state">
            <p>选择流水线并填入 gitSha 后触发运行；也可以从左侧构建历史点开一条。</p>
          </div>

          <template v-else>
            <div class="run-summary">
              <el-tag :type="elTagType(stageTone(run.status))" effect="light" round>
                {{ run.status }}
              </el-tag>
              <span class="hint">{{ run.stages.length }} 个 stage · {{ run.artifacts.length }} 个产物</span>
            </div>

            <div class="sub-title">
              Stage 时间线
            </div>
            <div class="timeline">
              <div v-for="(s, i) in run.stages" :key="stageKey(s, i)" class="stage" :class="stageTone(s.status)">
                <span class="dot" />
                <div class="stage-body">
                  <div class="stage-head">
                    <b class="mono">{{ s.id }}</b>
                    <span class="status-label">{{ s.status }}</span>
                    <span class="mono duration">{{ s.durationMs }} ms</span>
                  </div>
                  <div class="stage-meta mono">
                    exitCode={{ s.exitCode }}
                    <template v-if="s.errorCode"> · errorCode={{ s.errorCode }}</template>
                    <template v-if="s.summary"> · {{ s.summary }}</template>
                    <template v-if="s.artifacts"> · artifacts={{ s.artifacts }}</template>
                  </div>
                </div>
              </div>
              <div v-if="!run.stages.length" class="empty-hint">
                尚无 stage 结果。
              </div>
            </div>

            <div class="sub-title">
              产物
            </div>
            <div class="artifacts">
              <div v-for="(a, i) in run.artifacts" :key="artifactKey(a, i)" class="artifact mono">
                <span class="kind">{{ a.kind }}</span>
                <span class="ref" :title="a.ref">{{ a.ref }}</span>
                <span class="digest" :title="a.digest">{{ shortDigest(a.digest) }}</span>
                <span class="size">{{ a.sizeBytes }} B</span>
              </div>
              <div v-if="!run.artifacts.length" class="empty-hint">
                尚无产物。
              </div>
            </div>

            <div class="log-heading">
              <span class="sub-title">日志</span>
              <span class="hint">{{ logLineCount }} 行</span>
              <el-button size="small" text :icon="CopyDocument" @click="copyLog">
                复制
              </el-button>
            </div>
            <pre ref="logRef" class="log mono">{{ run.log || '（暂无日志）' }}</pre>
          </template>

          <div v-if="actionResult" class="action-result">
            <div class="sub-title">
              动作执行结果 · {{ actionResult.action }}
            </div>
            <div class="kv mono">
              status={{ actionResult.status }} exitCode={{ actionResult.exitCode }}
              <template v-if="actionResult.errorCode"> errorCode={{ actionResult.errorCode }}</template>
              · {{ actionResult.durationMs }} ms
            </div>
            <div v-if="actionResult.summary" class="kv">
              {{ actionResult.summary }}
            </div>
            <pre class="log mono">{{ actionResult.logTail || '（无日志）' }}</pre>
          </div>
          <div v-if="requestInfo" class="request-info mono">
            变更已登记：{{ requestInfo }}
          </div>
        </div>
      </section>
      </div>
    </div>

    <el-dialog v-model="changeDialog" title="L3 变更确认" width="560px">
      <div v-if="change" class="confirm-card">
        <div class="line">
          <span>变更单</span><b class="mono">{{ change.changeId }}</b>
        </div>
        <div class="line">
          <span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b>
        </div>
        <div class="line">
          <span>类型</span><b>{{ change.kind }}</b>
        </div>
        <div class="line">
          <span>内容</span><b>{{ change.summary }}</b>
        </div>
        <div class="line">
          <span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b>
        </div>
        <div class="line">
          <span>风险</span>
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small" effect="light" round>
            {{ change.highRisk ? '高危（需二次密码）' : '常规' }}
          </el-tag>
        </div>

        <el-input
          v-if="change.highRisk && !confirmToken"
          v-model="changePassword"
          type="password"
          placeholder="请输入密码（账号体系接入前会失败关闭）"
          show-password
        />

        <el-alert
          v-if="confirmToken"
          type="success"
          :closable="false"
          show-icon
          title="确认令牌已签发（5 分钟内有效，一次性）"
          :description="confirmToken"
        />

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="执行前会再次校验：令牌绑定的是当前配方，配方被改动则拒绝"
        />
      </div>

      <template #footer>
        <el-button @click="changeDialog = false">
          关闭
        </el-button>
        <el-button :disabled="!!confirmToken" :loading="changeBusy" @click="doConfirmChange">
          确认变更
        </el-button>
        <el-button
          type="primary"
          :disabled="!confirmToken || changeBusy"
          :loading="changeBusy"
          @click="doApplyChange"
        >
          带令牌执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面骨架：撑满主区，自身不滚动；滚动交给 .page-scroll */
.pipeline-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  color: var(--ink);
}
.page-scroll {
  flex: 1 1 0;
  min-height: 0;
  padding: 18px 20px 24px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.page-lead {
  max-width: 70ch;
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-soft);
}

/* 触发卡片 */
.trigger-card {
  padding: var(--workspace-panel-padding, 20px);
  margin-bottom: 20px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
}
.section-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.step {
  font: 600 11px/24px monospace;
  color: var(--accent);
}
.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.panel-count {
  font-size: 12px;
  font-weight: 400;
  color: #a0a6ad;
}
.trigger-form {
  display: grid;
  grid-template-columns: 180px 120px 200px minmax(220px, 1fr) auto;
  align-items: flex-end;
  gap: 12px;
}
.tf-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.tf-field label {
  font-size: 12px;
  color: var(--ink-soft);
}
.block-alert {
  margin-bottom: 20px;
}

/* 双栏 */
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(320px, 400px) minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}
.left-col {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}
.panel {
  min-width: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
}
.panel-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--workspace-panel-padding, 20px) var(--workspace-panel-padding, 20px) 0;
}
.panel-heading .el-button {
  margin-left: auto;
}
.panel-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px var(--workspace-panel-padding, 20px) var(--workspace-panel-padding, 20px);
}
.polling-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--accent);
}
.follow {
  margin-left: auto;
  font-weight: 400;
}

/* 动作卡 */
.action-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  transition: border-color 0.15s ease;
}
.action-card:hover {
  border-color: var(--accent);
}
.action-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.action-name {
  font-size: 13px;
  font-weight: 600;
}
.kv {
  display: flex;
  gap: 8px;
  font-size: 12px;
}
.kv-label {
  flex: none;
  color: #a0a6ad;
}
.kv-value {
  min-width: 0;
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cmd-fold summary {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
  cursor: pointer;
}
.commands {
  margin: 6px 0 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  max-height: 150px;
  overflow-y: auto;
}
.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 历史 */
.history-body {
  gap: 6px;
}
.history-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 96px 84px auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 14px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 10px;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.history-item:hover,
.history-item.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.hist-dot {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: #c0c4cc;
}
.hist-dot.ok {
  background: #67c23a;
}
.hist-dot.bad {
  background: #f56c6c;
}
.hist-dot.busy {
  background: var(--accent);
}
.hist-pipeline {
  min-width: 0;
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hist-sha {
  color: #a0a6ad;
  text-align: right;
}
.hist-status {
  font-size: 11px;
  color: #a0a6ad;
}
.hist-status.ok {
  color: #67c23a;
}
.hist-status.bad {
  color: #f56c6c;
}
.hist-time {
  font-size: 11px;
  color: #a0a6ad;
}

/* 运行详情 */
.empty-state {
  display: grid;
  min-height: 160px;
  place-items: center;
  font-size: 13px;
  color: #a0a6ad;
  text-align: center;
  background-image: radial-gradient(var(--line) 0.8px, transparent 0.8px);
  background-size: 18px 18px;
  border: 1px dashed var(--line);
  border-radius: 12px;
}
.empty-state p {
  margin: 0;
  padding: 16px;
}
.run-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sub-title {
  margin-top: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
}
.timeline {
  display: flex;
  flex-direction: column;
  margin-left: 6px;
  border-left: 2px solid var(--line);
}
.stage {
  display: flex;
  gap: 10px;
  margin-left: -7px;
  padding: 4px 0 10px;
}
.dot {
  width: 12px;
  height: 12px;
  flex: none;
  margin-top: 4px;
  background: #c0c4cc;
  border: 2px solid var(--panel);
  border-radius: 50%;
}
.stage.ok .dot {
  background: #67c23a;
}
.stage.bad .dot {
  background: #f56c6c;
}
.stage.busy .dot {
  background: var(--accent);
}
.stage-body {
  min-width: 0;
  flex: 1;
  padding: 8px 12px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-left: 3px solid #c0c4cc;
  border-radius: 10px;
}
.stage.ok .stage-body {
  background: #f0f9eb;
  border-left-color: #67c23a;
}
.stage.bad .stage-body {
  background: #fef0f0;
  border-left-color: #f56c6c;
}
.stage.busy .stage-body {
  background: var(--accent-soft);
  border-left-color: var(--accent);
}
.stage-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.status-label {
  font-size: 12px;
  color: var(--ink-soft);
}
.stage.ok .status-label {
  color: #529b2e;
}
.stage.bad .status-label {
  color: #c45656;
}
.duration {
  margin-left: auto;
  color: #a0a6ad;
}
.stage-meta {
  margin-top: 4px;
  color: var(--ink-soft);
  word-break: break-all;
}

/* 产物 */
.artifacts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.artifact {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 170px 80px;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
}
.kind {
  color: var(--accent);
}
.ref,
.digest {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.digest {
  color: var(--ink-soft);
}
.size {
  color: #a0a6ad;
  text-align: right;
}

/* 日志 */
.log-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.log-heading .el-button {
  margin-left: auto;
}
.log {
  margin: 0;
  padding: 12px 14px;
  font-size: 12px;
  line-height: 1.6;
  color: #d7e4f0;
  white-space: pre-wrap;
  word-break: break-all;
  background: #1e2430;
  border-radius: 10px;
  max-height: 300px;
  overflow-y: auto;
}
.action-result {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}
.request-info {
  padding: 10px 12px;
  color: #9a4514;
  word-break: break-all;
  background: #fdf6ec;
  border: 1px solid #f5d3b8;
  border-radius: 10px;
}
.empty-hint {
  padding: 4px 0;
  font-size: 12px;
  color: #a0a6ad;
}

/* L3 确认卡片 */
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
  color: var(--ink-soft);
}

/* 自适应（对齐参考项目的 container query 思路） */
@media (max-width: 1100px) {
  .trigger-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .workbench-grid {
    grid-template-columns: minmax(300px, 0.9fr) minmax(0, 1.1fr);
    gap: 16px;
  }
}
@media (max-width: 820px) {
  .workbench-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .trigger-form {
    grid-template-columns: minmax(0, 1fr);
  }
  .artifact {
    grid-template-columns: 56px minmax(0, 1fr) 120px;
  }
  .artifact .size {
    display: none;
  }
}
</style>
