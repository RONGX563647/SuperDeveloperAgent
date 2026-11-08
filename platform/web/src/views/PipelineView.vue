<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
const appliedRun = ref<RunView | null>(null)

// 日志面板
const followLog = ref(true)
const logRef = ref<HTMLElement | null>(null)

const POLL_INTERVAL_MS = 1000

const polling = computed(() => pollTimer !== null)
const canTrigger = computed(() => !!selectedPipeline.value && /^[0-9a-f]{7,40}$/.test(gitSha.value))
const runActive = computed(() => !!run.value && !isTerminalStatus(run.value.status))

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** digest 截断显示：保留前缀与短尾，避免长哈希撑破布局。 */
function shortDigest(digest: string): string {
  const value = digest || '—'
  if (value.length <= 20) return value
  return `${value.slice(0, 16)}…${value.slice(-6)}`
}

function stageClass(status: string): string {
  if (status === 'SUCCEEDED') return 'ok'
  if (['FAILED', 'TIMEOUT', 'CANCELLED'].includes(status)) return 'bad'
  return 'busy'
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
  } catch (error) {
    // 历史加载失败不打断主流程，只在面板里显示为空
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

/** 从历史里点开一条：直接恢复完整运行视图。 */
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

/** 列表里没带命令原文时，点击「查看计划」按需取一次并缓存。 */
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

/** L3 第一步：登记变更，随后弹出确认卡片完成「确认 → 执行」闭环。 */
async function doRequestAction(action: string) {
  actionBusy.value = action
  actionResult.value = null
  requestInfo.value = ''
  confirmToken.value = null
  appliedRun.value = null
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

/** L3 第二步：带令牌执行。结果直接进运行面板（同步返回终态）。 */
async function doApplyChange() {
  if (!change.value || !confirmToken.value) return
  changeBusy.value = true
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    appliedRun.value = {
      runId: `${change.value.changeId}（动作 ${result.action}）`,
      status: result.status,
      stages: [],
      artifacts: result.artifacts,
      log: result.logTail
    }
    run.value = appliedRun.value
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

/** 新一次运行前清空上一次的展示数据。 */
function clearRunView() {
  run.value = null
  runId.value = ''
  actionResult.value = null
  requestInfo.value = ''
  pollCount.value = 0
}

/** 每 1s 拉取一次运行状态，直到进入终态（SUCCEEDED/FAILED/TIMEOUT/CANCELLED）。 */
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
    // 先取一次，避免首屏空白，再进入 1s 轮询
    run.value = await getRun(result.runId)
    if (!isTerminalStatus(run.value.status)) startPolling(result.runId)
    void loadHistory()
  } catch (error) {
    errorText.value = `触发流水线失败：${messageOf(error)}`
  } finally {
    runBusy.value = false
  }
}

/** 轮询期间日志自动滚到底部（可关闭跟随）。 */
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
    <div class="page-head">
      <h2>流水线 / 动作面板</h2>
      <span class="hint">引擎 <b class="mono">{{ engine || '—' }}</b> · 运行结果每 1s 刷新至终态</span>
    </div>

    <div class="toolbar">
      <div class="field">
        <label>项目</label>
        <el-select v-model="projectId" size="small" style="width: 180px">
          <el-option label="RY · RuoYi-Vue-Plus" value="RY" />
        </el-select>
      </div>
      <div class="field">
        <label>环境</label>
        <el-select v-model="env" size="small" style="width: 120px">
          <el-option label="dev" value="dev" />
          <el-option label="test" value="test" />
          <el-option label="prod" value="prod" />
        </el-select>
      </div>
      <div class="field grow">
        <label>流水线</label>
        <el-select v-model="selectedPipeline" size="small" :loading="pipelinesLoading" style="width: 200px">
          <el-option v-for="p in pipelineList" :key="p" :label="p" :value="p" />
        </el-select>
      </div>
      <div class="field grow">
        <label>gitSha（7-40 位小写十六进制）</label>
        <el-input v-model="gitSha" class="mono" size="small" placeholder="例如 a1b2c3d" clearable />
      </div>
      <el-button type="primary" size="small" :loading="runBusy" :disabled="!canTrigger" @click="doTrigger">
        触发运行
      </el-button>
      <el-button
        v-if="runActive"
        size="small"
        type="warning"
        plain
        @click="doCancel"
      >
        取消运行
      </el-button>
    </div>

    <el-alert v-if="errorText" class="block" type="error" :closable="false" :title="errorText" />

    <div class="columns">
      <div class="left-col">
        <section class="panel actions-panel">
          <div class="panel-title">
            动作列表
            <span class="hint">{{ projectId }} / {{ env }} · {{ actions.length }} 项</span>
          </div>
          <div v-loading="actionsLoading" class="panel-body">
            <div v-for="a in actions" :key="a.action" class="action-card">
              <div class="action-head">
                <b class="mono">{{ a.action }}</b>
                <el-tag size="small" :type="a.level.startsWith('L3') ? 'warning' : 'info'">{{ a.level }}</el-tag>
                <el-tag v-if="a.humanAuthored" size="small" type="success">人写配方</el-tag>
              </div>
              <div class="kv">来源 <span class="mono">{{ a.source }}</span></div>
              <div class="kv">产物 <span class="mono">{{ a.produce.join(', ') || '—' }}</span></div>
              <div class="kv">超时 <span class="mono">{{ a.timeoutMinutes }} 分钟</span></div>

              <div class="commands-label">命令原文</div>
              <pre class="commands mono">{{ a.commands.length ? a.commands.join('\n') : (planCache[a.action]?.commands.join('\n') ?? '（列表未返回命令，点「查看计划」读取）') }}</pre>

              <div class="action-buttons">
                <el-button size="small" :loading="actionBusy === a.action" @click="showPlan(a.action)">查看计划</el-button>
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

        <section class="panel history-panel">
          <div class="panel-title">
            构建历史
            <span class="hint">{{ history.length }} 条 · 最新在前</span>
            <el-button size="small" text :loading="historyLoading" @click="loadHistory">刷新</el-button>
          </div>
          <div v-loading="historyLoading" class="panel-body history-body">
            <div
              v-for="h in history"
              :key="h.runId"
              class="history-item"
              :class="{ active: h.runId === runId }"
              @click="openHistory(h)"
            >
              <el-tag size="small" :type="stageClass(h.status) === 'ok' ? 'success' : stageClass(h.status) === 'bad' ? 'danger' : 'info'">
                {{ h.status }}
              </el-tag>
              <span class="mono hist-pipeline">{{ h.pipelineId }}</span>
              <span class="mono hist-sha" :title="h.gitSha">{{ shortSha(h.gitSha) }}</span>
              <span class="hist-time mono">{{ formatTime(h.createdAt) }}</span>
            </div>
            <el-empty v-if="!historyLoading && !history.length" description="暂无构建历史" :image-size="60" />
          </div>
        </section>
      </div>

      <section class="panel run-panel">
        <div class="panel-title">
          运行状态
          <span v-if="runId" class="mono">{{ runId }}</span>
          <span v-if="polling" class="hint polling">● 轮询中（第 {{ pollCount }} 次）</span>
          <el-checkbox v-model="followLog" size="small" class="follow">跟随日志</el-checkbox>
        </div>
        <div class="panel-body">
          <div v-if="!run" class="empty-hint">选择流水线并填入 gitSha 后触发运行；也可从左侧构建历史点开一条。</div>

          <template v-else>
            <div class="run-summary">
              <span>状态</span>
              <el-tag :type="stageClass(run.status) === 'ok' ? 'success' : stageClass(run.status) === 'bad' ? 'danger' : 'primary'" size="small">
                {{ run.status }}
              </el-tag>
              <span class="hint">{{ run.stages.length }} 个 stage · {{ run.artifacts.length }} 个产物</span>
              <el-button v-if="runActive" size="small" type="warning" plain @click="doCancel">取消</el-button>
            </div>

            <div class="sub-title">Stage 时间线</div>
            <div class="timeline">
              <div v-for="(s, i) in run.stages" :key="stageKey(s, i)" class="stage" :class="stageClass(s.status)">
                <div class="dot" />
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
              <div v-if="!run.stages.length" class="empty-hint">尚无 stage 结果。</div>
            </div>

            <div class="sub-title">产物列表</div>
            <div class="artifacts">
              <div v-for="(a, i) in run.artifacts" :key="artifactKey(a, i)" class="artifact mono">
                <span class="kind">{{ a.kind }}</span>
                <span class="ref" :title="a.ref">{{ a.ref }}</span>
                <span class="digest" :title="a.digest">{{ shortDigest(a.digest) }}</span>
                <span class="size">{{ a.sizeBytes }} B</span>
              </div>
              <div v-if="!run.artifacts.length" class="empty-hint">尚无产物。</div>
            </div>

            <div class="sub-title log-head">
              日志尾部
              <span class="hint">{{ (run.log || '').split('\n').length }} 行</span>
              <el-button size="small" text @click="copyLog">复制</el-button>
            </div>
            <pre ref="logRef" class="log mono">{{ run.log || '（暂无日志）' }}</pre>
          </template>

          <div v-if="actionResult" class="action-result">
            <div class="sub-title">动作执行结果 · {{ actionResult.action }}</div>
            <div class="kv mono">
              status={{ actionResult.status }} exitCode={{ actionResult.exitCode }}
              <template v-if="actionResult.errorCode"> errorCode={{ actionResult.errorCode }}</template>
              · {{ actionResult.durationMs }} ms
            </div>
            <div v-if="actionResult.summary" class="kv">{{ actionResult.summary }}</div>
            <pre class="log mono">{{ actionResult.logTail || '（无日志）' }}</pre>
          </div>
          <div v-if="requestInfo" class="request-info mono">变更已登记：{{ requestInfo }}</div>
        </div>
      </section>
    </div>

    <el-dialog v-model="changeDialog" title="L3 变更确认" width="560px">
      <div v-if="change" class="confirm-card">
        <div class="line"><span>变更单</span><b class="mono">{{ change.changeId }}</b></div>
        <div class="line"><span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b></div>
        <div class="line"><span>类型</span><b>{{ change.kind }}</b></div>
        <div class="line"><span>内容</span><b>{{ change.summary }}</b></div>
        <div class="line"><span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b></div>
        <div class="line">
          <span>风险</span>
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small">
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
          title="确认令牌已签发（5 分钟内有效，一次性）"
          :description="confirmToken"
        />

        <el-alert
          type="info"
          :closable="false"
          title="执行前会再次校验：令牌绑定的是当前配方，配方被改动则拒绝"
        />
      </div>

      <template #footer>
        <el-button @click="changeDialog = false">关闭</el-button>
        <el-button :disabled="!!confirmToken" :loading="changeBusy" @click="doConfirmChange">确认变更</el-button>
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
.pipeline-page {
  height: 100%;
  overflow-y: auto;
  padding: 16px 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.page-head {
  display: flex;
  align-items: baseline;
  gap: 16px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
  color: var(--ink);
}
.hint {
  font-size: 12px;
  color: #8a98a6;
}
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field.grow {
  flex: 1 1 180px;
}
.field label {
  font-size: 12px;
  color: #6b7a88;
}
.block {
  flex: none;
}
.columns {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(360px, 1.2fr);
  gap: 12px;
  align-items: start;
}
.left-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.panel {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  min-width: 0;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.polling {
  color: var(--accent);
}
.follow {
  margin-left: auto;
  font-weight: 400;
}
.panel-body {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.history-body {
  gap: 4px;
}
.history-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 96px auto;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fafbfc;
  cursor: pointer;
}
.history-item:hover {
  border-color: var(--accent);
  background: #fdf6f0;
}
.history-item.active {
  border-color: var(--accent);
  background: #fdeee4;
}
.hist-pipeline {
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hist-sha {
  color: #6b7a88;
  text-align: right;
}
.hist-time {
  color: #8a98a6;
  font-size: 11px;
}
.action-card {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.action-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.kv {
  font-size: 12px;
  color: var(--ink-soft);
  word-break: break-all;
}
.commands-label,
.sub-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7a88;
}
.log-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.commands {
  margin: 0;
  padding: 8px 10px;
  background: #f7f9fb;
  border: 1px solid var(--line);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
  color: var(--ink);
  max-height: 140px;
  overflow-y: auto;
}
.action-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.empty-hint {
  font-size: 12px;
  color: #8a98a6;
  padding: 4px 0;
}
.run-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.timeline {
  display: flex;
  flex-direction: column;
  border-left: 2px solid var(--line);
  margin-left: 6px;
}
.stage {
  display: flex;
  gap: 10px;
  padding: 4px 0 10px 0;
  margin-left: -7px;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid #fff;
  background: #8a98a6;
  flex: none;
  margin-top: 2px;
}
.stage.ok .dot {
  background: #2e9e5b;
}
.stage.bad .dot {
  background: #c0392b;
}
.stage.busy .dot {
  background: var(--accent);
}
.stage-body {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-left: 3px solid #8a98a6;
  border-radius: 4px;
  background: #fafbfc;
}
.stage.ok .stage-body {
  border-left-color: #2e9e5b;
  background: #f2fbf5;
}
.stage.bad .stage-body {
  border-left-color: #c0392b;
  background: #fdf3f2;
}
.stage.busy .stage-body {
  border-left-color: var(--accent);
  background: #fdf6f0;
}
.stage-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.status-label {
  font-size: 12px;
  color: var(--ink-soft);
}
.stage.ok .status-label {
  color: #2e9e5b;
}
.stage.bad .status-label {
  color: #c0392b;
}
.duration {
  margin-left: auto;
  color: #6b7a88;
}
.stage-meta {
  margin-top: 4px;
  color: var(--ink-soft);
  word-break: break-all;
}
.artifacts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.artifact {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr) 180px 90px;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fafbfc;
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
  text-align: right;
  color: #8a98a6;
}
.log {
  margin: 0;
  padding: 10px;
  background: #12263a;
  color: #d7e4f0;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
  max-height: 260px;
  overflow-y: auto;
}
.action-result {
  border-top: 1px dashed var(--line);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.request-info {
  padding: 8px 10px;
  border: 1px solid #f5d3b8;
  background: #fdeee4;
  border-radius: 4px;
  color: #9a4514;
  word-break: break-all;
}
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.confirm-card .line {
  display: flex;
  gap: 12px;
  font-size: 13px;
}
.confirm-card .line span {
  width: 90px;
  flex: none;
  color: #6b7a88;
}
@media (max-width: 900px) {
  .columns {
    grid-template-columns: 1fr;
  }
}
</style>
