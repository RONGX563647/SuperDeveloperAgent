<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getActionPlan,
  listActions,
  requestAction,
  runAction,
  type ActionRunView,
  type ActionView
} from '../api/action'
import {
  getRun,
  isTerminalStatus,
  listPipelines,
  triggerPipeline,
  type ArtifactView,
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

const POLL_INTERVAL_MS = 1000

const polling = computed(() => pollTimer !== null)
const canTrigger = computed(() => !!selectedPipeline.value && /^[0-9a-f]{7,40}$/.test(gitSha.value))

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

async function doRequestAction(action: string) {
  actionBusy.value = action
  actionResult.value = null
  requestInfo.value = ''
  try {
    const result = await requestAction(projectId.value, env.value, action)
    requestInfo.value = `${result.changeId} · planHash ${result.planHash.slice(0, 24)}…`
    ElMessage.success('变更已登记，等待人工确认')
  } catch (error) {
    ElMessage.error(`登记失败：${messageOf(error)}`)
  } finally {
    actionBusy.value = ''
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
      if (isTerminalStatus(data.status)) stopPolling()
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
  } catch (error) {
    errorText.value = `触发流水线失败：${messageOf(error)}`
  } finally {
    runBusy.value = false
  }
}

watch([projectId, env], () => {
  void loadPipelines()
  void loadActions()
})

onMounted(() => {
  void loadPipelines()
  void loadActions()
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
    </div>

    <el-alert v-if="errorText" class="block" type="error" :closable="false" :title="errorText" />

    <div class="columns">
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

      <section class="panel run-panel">
        <div class="panel-title">
          运行状态
          <span v-if="runId" class="mono">{{ runId }}</span>
          <span v-if="polling" class="hint polling">● 轮询中（第 {{ pollCount }} 次）</span>
        </div>
        <div class="panel-body">
          <div v-if="!run" class="empty-hint">选择流水线并填入 gitSha 后触发运行。</div>

          <template v-else>
            <div class="run-summary">
              <span>状态</span>
              <el-tag :type="stageClass(run.status) === 'ok' ? 'success' : stageClass(run.status) === 'bad' ? 'danger' : 'primary'" size="small">
                {{ run.status }}
              </el-tag>
              <span class="hint">{{ run.stages.length }} 个 stage · {{ run.artifacts.length }} 个产物</span>
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

            <div class="sub-title">日志尾部</div>
            <pre class="log mono">{{ run.log || '（暂无日志）' }}</pre>
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
.panel-body {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
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
@media (max-width: 900px) {
  .columns {
    grid-template-columns: 1fr;
  }
}
</style>
