<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CopyDocument, Loading, Refresh, Remove, VideoPlay } from '@element-plus/icons-vue'
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
import { appStore } from '../stores/app'
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

/** 项目/环境与聊天页共用全局状态：切一次，两边同步。 */
const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)

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

// 日志
const followLog = ref(true)
const logRef = ref<HTMLElement | null>(null)

const POLL_INTERVAL_MS = 1000

const polling = computed(() => pollTimer !== null)
const canTrigger = computed(() => !!selectedPipeline.value && /^[0-9a-f]{7,40}$/.test(gitSha.value))
const runActive = computed(() => !!run.value && !isTerminalStatus(run.value.status))
const logLineCount = computed(() => (run.value?.log ?? '').split('\n').filter((l) => l.trim()).length)
/** 最近一次成功 sha：触发框的快捷项（最高频操作不该靠复制粘贴）。 */
const lastGoodSha = computed(() => history.value.find((h) => h.status === 'SUCCEEDED')?.gitSha ?? '')
const anyHistorySha = computed(() => history.value[0]?.gitSha ?? '')

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
    // 空输入时自动预填最近一次构建的 sha，少一步复制粘贴
    if (!gitSha.value && history.value.length) {
      gitSha.value = history.value[0].gitSha.slice(0, 7)
    }
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
    gitSha.value = item.gitSha.slice(0, 7)
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

/** 一键重跑：用历史条目里的 sha 重新触发同一条流水线。 */
async function rerun(item: HistoryItem) {
  gitSha.value = item.gitSha.slice(0, 7)
  selectedPipeline.value = item.pipelineId
  await doTrigger()
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
    <PageHeader title="流水线工作台" :subtitle="`${projectId} · ${env} · 引擎 ${engine || '—'}`">
      <template #actions>
        <span v-if="polling" class="polling-chip">
          <el-icon class="is-loading"><Loading /></el-icon>
          运行中
        </span>
        <el-button v-if="runActive" size="small" type="warning" plain @click="doCancel">取消</el-button>
      </template>
    </PageHeader>

    <div class="page-scroll">
      <!-- 触发：一行搞定（默认已预填最近提交，通常只需点一次） -->
      <div class="trigger-bar">
        <el-select v-model="selectedPipeline" class="t-pipeline" size="default">
          <el-option v-for="p in pipelineList" :key="p" :label="p" :value="p" />
        </el-select>
        <el-input
          v-model="gitSha"
          class="t-sha mono"
          size="default"
          placeholder="commit sha（已自动预填最近一次）"
          clearable
        />
        <el-button
          type="primary"
          :icon="VideoPlay"
          :loading="runBusy"
          :disabled="!canTrigger"
          @click="doTrigger"
        >
          运行
        </el-button>
        <span v-if="lastGoodSha" class="t-quick mono" title="最近一次成功构建的提交">
          上次成功：<a class="t-quick-sha" @click.prevent="gitSha = lastGoodSha.slice(0, 7)">{{ lastGoodSha.slice(0, 7) }}</a>
        </span>
      </div>

      <el-alert v-if="errorText" class="block-alert" type="error" :closable="false" :title="errorText" show-icon />

      <div class="grid">
        <!-- 左：构建历史为主，动作收进下拉 -->
        <div class="col-left">
          <section class="panel" aria-label="构建历史">
            <div class="panel-heading">
              <h2 class="section-title">构建历史</h2>
              <span class="panel-count">{{ history.length }} 条</span>
              <el-button size="small" text :icon="Refresh" :loading="historyLoading" @click="loadHistory" />
            </div>
            <div v-loading="historyLoading" class="panel-body history-body">
              <div
                v-for="h in history"
                :key="h.runId"
                class="history-item"
                :class="{ active: h.runId === runId }"
                role="button"
                tabindex="0"
                @click="openHistory(h)"
                @keydown.enter="openHistory(h)"
              >
                <span class="hist-dot" :class="stageTone(h.status)" />
                <div class="hist-main">
                  <div class="hist-line1">
                    <span class="hist-pipeline">{{ h.pipelineId }}</span>
                    <span class="hist-status" :class="stageTone(h.status)">{{ h.status }}</span>
                  </div>
                  <div class="hist-line2 mono">
                    <span :title="h.gitSha">{{ shortSha(h.gitSha) }}</span>
                    <span class="hist-time">{{ formatTime(h.createdAt) }}</span>
                  </div>
                </div>
                <el-button
                  class="hist-rerun"
                  size="small"
                  text
                  :icon="Refresh"
                  title="用该提交重跑"
                  @click.stop="rerun(h)"
                />
              </div>
              <el-empty v-if="!historyLoading && !history.length" description="暂无构建历史" :image-size="60" />
            </div>
          </section>

          <section class="panel" aria-label="快捷动作">
            <div class="panel-heading">
              <h2 class="section-title">快捷动作</h2>
              <span class="panel-count">单项执行 · 生产受限</span>
            </div>
            <div v-loading="actionsLoading" class="panel-body actions-body">
              <div v-for="a in actions" :key="a.action" class="action-row">
                <div class="action-main">
                  <span class="mono action-name">{{ a.action }}</span>
                  <el-tag size="small" :type="a.level.startsWith('L3') ? 'warning' : 'info'" effect="light" round>
                    {{ a.level.startsWith('L3') ? '需确认' : '可直接执行' }}
                  </el-tag>
                </div>
                <div class="action-ops">
                  <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'plan' ? showPlan(a.action) : cmd === 'run' ? doRunAction(a.action) : doRequestAction(a.action)">
                    <el-button size="small" text :loading="actionBusy === a.action">
                      操作<el-icon class="el-icon--right"><Remove /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="plan">查看命令原文</el-dropdown-item>
                        <el-dropdown-item v-if="!a.level.startsWith('L3')" command="run" divided>执行</el-dropdown-item>
                        <el-dropdown-item v-else command="req" divided>登记变更…</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
                <pre v-if="planCache[a.action]" class="action-cmd mono">{{ planCache[a.action].commands.join('\n') }}</pre>
              </div>
              <el-empty v-if="!actionsLoading && !actions.length" description="暂无动作" :image-size="60" />
            </div>
          </section>
        </div>

        <!-- 右：运行详情（无 run 时不占一整块，改为轻量引导） -->
        <section class="panel detail-panel" aria-label="运行详情">
          <div class="panel-heading">
            <h2 class="section-title">运行详情</h2>
            <span v-if="runId" class="mono panel-count">{{ runId }}</span>
            <el-checkbox v-model="followLog" size="small" class="follow">
              跟随日志
            </el-checkbox>
          </div>
          <div class="panel-body detail-body">
            <div v-if="!run" class="empty-state">
              <p>触发运行，或从左侧历史点开一条查看 stage、产物与日志。</p>
            </div>

            <template v-else>
              <div class="run-summary">
                <el-tag :type="elTagType(stageTone(run.status))" effect="light" round>{{ run.status }}</el-tag>
                <span class="hint">{{ run.stages.length }} 个 stage · {{ run.artifacts.length }} 个产物</span>
              </div>

              <div v-if="run.stages.length" class="sub-title">
                Stage
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
                    <div v-if="s.errorCode || s.summary" class="stage-meta mono">
                      <template v-if="s.errorCode">errorCode={{ s.errorCode }} · </template>
                      <template v-if="s.summary">{{ s.summary }}</template>
                    </div>
                  </div>
                </div>
              </div>

              <template v-if="run.artifacts.length">
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
                </div>
              </template>

              <div class="log-heading">
                <span class="sub-title">日志</span>
                <span class="hint">{{ logLineCount }} 行</span>
                <el-button size="small" text :icon="CopyDocument" @click="copyLog">复制</el-button>
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
              <pre class="log mono">{{ actionResult.logTail || '（无日志）' }}</pre>
            </div>
            <div v-if="requestInfo" class="request-info mono">变更已登记：{{ requestInfo }}</div>
          </div>
        </section>
      </div>
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
        <el-button @click="changeDialog = false">关闭</el-button>
        <el-button :disabled="!!confirmToken" :loading="changeBusy" @click="doConfirmChange">确认变更</el-button>
        <el-button type="primary" :disabled="!confirmToken || changeBusy" :loading="changeBusy" @click="doApplyChange">带令牌执行</el-button>
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
  padding: 16px 20px 24px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.polling-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--accent);
}
.block-alert {
  margin-bottom: 14px;
}

/* 触发条：一行式，替代原四字段表单 */
.trigger-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
}
.t-pipeline {
  width: 180px;
}
.t-sha {
  flex: 1 1 220px;
  min-width: 0;
}
.t-quick {
  flex: none;
  font-size: 11px;
  color: #a0a6ad;
}
.t-quick-sha {
  color: var(--accent);
  cursor: pointer;
  text-decoration: underline dotted;
}

/* 双栏 */
.grid {
  display: grid;
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}
.col-left {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.panel {
  min-width: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
}
.panel-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px 0;
}
.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.panel-count {
  font-size: 12px;
  font-weight: 400;
  color: #a0a6ad;
}
.panel-heading .el-button {
  margin-left: auto;
}
.panel-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px 16px;
}
.follow {
  margin-left: auto;
  font-weight: 400;
}

/* 历史 */
.history-body {
  gap: 6px;
}
.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
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
  flex: none;
  width: 8px;
  height: 8px;
  background: #c0c4cc;
  border-radius: 50%;
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
.hist-main {
  flex: 1;
  min-width: 0;
}
.hist-line1,
.hist-line2 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.hist-pipeline {
  font-size: 13px;
  font-weight: 600;
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
.hist-line2 {
  margin-top: 3px;
  font-size: 11px;
  color: #a0a6ad;
}
.hist-rerun {
  flex: none;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.history-item:hover .hist-rerun {
  opacity: 1;
}

/* 动作（下拉操作，信息收敛） */
.actions-body {
  gap: 6px;
}
.action-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.action-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.action-name {
  font-size: 13px;
  font-weight: 600;
}
.action-ops {
  display: flex;
  flex: none;
}
.action-cmd {
  margin: 0;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  max-height: 120px;
  overflow-y: auto;
}

/* 详情 */
.detail-body {
  gap: 10px;
}
.empty-state {
  display: grid;
  min-height: 120px;
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
  padding: 14px;
}
.run-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sub-title {
  margin-top: 4px;
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
  padding: 3px 0 8px;
}
.dot {
  flex: none;
  width: 12px;
  height: 12px;
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
  padding: 7px 11px;
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
  margin-top: 3px;
  font-size: 11px;
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
  grid-template-columns: 56px minmax(0, 1fr) 150px 80px;
  gap: 10px;
  padding: 7px 11px;
  font-size: 11px;
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
.log-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
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
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}
.kv {
  font-size: 12px;
  color: var(--ink-soft);
  word-break: break-all;
}
.request-info {
  padding: 9px 12px;
  font-size: 12px;
  color: #9a4514;
  word-break: break-all;
  background: #fdf6ec;
  border: 1px solid #f5d3b8;
  border-radius: 10px;
}
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
.hint {
  font-size: 12px;
  color: #a0a6ad;
}

@media (max-width: 1000px) {
  .grid {
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