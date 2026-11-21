<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getDeploymentLogs,
  getDeploymentMatrix,
  restartDeployment,
  startDeployment,
  stopDeployment,
  type DeploymentCell,
  type DeploymentMatrix,
  type RetainedImage,
} from '../../api/workspace'
import { triggerPipeline } from '../../api/pipeline'
import { envShort } from '../../utils/pipelineLabels'
import { formatDateTime, relativeTime } from '../../utils/status'

/**
 * 环境与版本：按环境展示流水线部署出的产物（HOST jar 进程 / DOCKER 容器）。
 *
 * <p>卡片只展示概要；点击卡片打开详情抽屉，可查看完整部署信息、启动 / 停止 / 重启，
 * 以及容器日志（HOST 读 app.log）。重启沿用原端口，只有端口被占用才漂移。
 */
const props = defineProps<{ projectId: string }>()

const matrix = ref<DeploymentMatrix | null>(null)
const loading = ref(false)
const error = ref('')
const acting = ref('')
/** 正在按镜像 tag 触发重新构建的行（构建按钮转圈）。 */
const building = ref('')
let timer: ReturnType<typeof setInterval> | null = null

/** 最近保留的镜像（镜像账本，按部署时间倒序）。 */
const retainedImages = computed<RetainedImage[]>(() => matrix.value?.images ?? [])

function sizeLabel(bytes: number): string {
  if (!bytes) return '—'
  const gb = bytes / (1024 * 1024 * 1024)
  return gb >= 1 ? `${gb.toFixed(1)} GB` : `${Math.round(bytes / (1024 * 1024))} MB`
}

/** 按保留镜像的提交直接触发一次 test 环境重新构建（不打 tag，覆盖默认部署槽）。 */
async function rebuild(image: RetainedImage): Promise<void> {
  if (!image.gitSha || image.gitSha.length < 7) {
    ElMessage.warning('该镜像没有记录提交 sha，无法按提交重新构建；请在流水线页手动触发')
    return
  }
  building.value = image.tag
  try {
    const run = await triggerPipeline('release', props.projectId, image.gitSha, 'docker', 'test')
    ElMessage.success(`已触发重新构建：${image.version || image.tag}（runId ${run.runId}）`)
  } catch (e) {
    ElMessage.error(`触发失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    building.value = ''
  }
}

const drawerOpen = ref(false)
const selectedKey = ref('')

const logLines = ref<string[]>([])
const logLoading = ref(false)
const logError = ref('')
const logContainer = ref('')
const logContainers = ref<string[]>([])
const logSource = ref<'DOCKER' | 'HOST'>('HOST')
const logNote = ref('')
const logAuto = ref(true)
const logBox = ref<HTMLElement | null>(null)
let logTimer: ReturnType<typeof setInterval> | null = null

const LOG_TAIL = 400

const envs = computed(() => matrix.value?.environments ?? [])
const cells = computed(() => matrix.value?.cells ?? [])
const hasRows = computed(() => cells.value.length > 0)
const runningCount = computed(() => cells.value.filter((cell) => cell.running).length)

const selectedCell = computed<DeploymentCell | null>(() => {
  if (!selectedKey.value) return null
  return cells.value.find((cell) => keyOf(cell) === selectedKey.value) ?? null
})

function keyOf(cell: DeploymentCell): string {
  return `${cell.env}|${cell.version}`
}

function cellsOfEnv(env: string): DeploymentCell[] {
  return cells.value
    .filter((cell) => cell.env === env)
    .slice()
    .sort((a, b) => Number(b.running) - Number(a.running)
      || Number(b.current) - Number(a.current)
      || b.deployedAt - a.deployedAt)
}

function runningOfEnv(env: string): number {
  return cellsOfEnv(env).filter((cell) => cell.running).length
}

function shortVersion(version: string): string {
  if (!version || version === 'latest') return 'latest'
  return version.length > 18 ? `${version.slice(0, 17)}…` : version
}

function stateLabel(cell: DeploymentCell): string {
  if (cell.state === 'RUNNING') return '运行中'
  if (cell.state === 'STOPPED') return '已停止'
  return cell.state
}

function logSourceLabel(): string {
  return logSource.value === 'DOCKER' ? '容器日志' : '进程日志'
}

async function load(): Promise<void> {
  if (!props.projectId) return
  loading.value = true
  error.value = ''
  try {
    matrix.value = await getDeploymentMatrix(props.projectId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '环境信息加载失败'
  } finally {
    loading.value = false
  }
}

async function loadLogs(silent = false): Promise<void> {
  const cell = selectedCell.value
  if (!props.projectId || !cell) return
  if (!silent) {
    logLoading.value = true
    logError.value = ''
  }
  try {
    const result = await getDeploymentLogs(props.projectId, cell.env, {
      version: cell.version,
      container: logContainer.value || undefined,
      tail: LOG_TAIL,
    })
    logLines.value = result.lines
    logContainers.value = result.containers
    logSource.value = result.source
    logNote.value = result.note
    logError.value = ''
    if (result.container) {
      logContainer.value = result.container
    } else if (!logContainer.value && result.containers.length) {
      logContainer.value = result.containers[0]
    }
  } catch (e) {
    if (!silent) {
      logError.value = e instanceof Error ? e.message : '日志加载失败'
    }
  } finally {
    if (!silent) logLoading.value = false
  }
}

function openDetail(cell: DeploymentCell): void {
  selectedKey.value = keyOf(cell)
  logContainer.value = cell.containers[0] ?? ''
  logLines.value = []
  logError.value = ''
  logContainers.value = []
  drawerOpen.value = true
  void loadLogs()
}

function onContainerChange(): void {
  void loadLogs()
}

async function toggle(cell: DeploymentCell): Promise<void> {
  const key = keyOf(cell)
  if (acting.value) return
  acting.value = key
  try {
    const result = cell.running
      ? await stopDeployment(props.projectId, cell.env, cell.version)
      : await startDeployment(props.projectId, cell.env, cell.version)
    ElMessage.success(result.message)
    await load()
    if (drawerOpen.value) await loadLogs(true)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    acting.value = ''
  }
}

async function restart(cell: DeploymentCell): Promise<void> {
  const key = keyOf(cell)
  if (acting.value) return
  acting.value = key
  try {
    const result = await restartDeployment(props.projectId, cell.env, cell.version)
    ElMessage.success(result.message)
    await load()
    if (drawerOpen.value) await loadLogs(true)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重启失败')
  } finally {
    acting.value = ''
  }
}

async function scrollLogToBottom(): Promise<void> {
  await nextTick()
  if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
}

watch(logLines, () => {
  if (logAuto.value) void scrollLogToBottom()
})

watch(drawerOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (!open) {
    selectedKey.value = ''
    logLines.value = []
    logError.value = ''
    logContainers.value = []
    logContainer.value = ''
  }
})

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && drawerOpen.value) {
    drawerOpen.value = false
  }
}

onMounted(() => {
  void load()
  window.addEventListener('keydown', onKeydown)
  // 部署状态可能被流水线改变：停留在这个 Tab 时轻量轮询
  timer = setInterval(() => {
    if (!acting.value) void load()
  }, 10_000)
  // 详情打开且跟随日志时，每 3 秒刷新一次日志
  logTimer = setInterval(() => {
    if (drawerOpen.value && logAuto.value && !acting.value) void loadLogs(true)
  }, 3_000)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
  if (timer) clearInterval(timer)
  if (logTimer) clearInterval(logTimer)
  timer = null
  logTimer = null
})
</script>

<template>
  <section class="dv">
    <header class="dv-head">
      <div class="dv-title">
        <h2>环境与版本</h2>
        <p>流水线部署成功的前后端产物，按环境独立启停；点击卡片查看部署详情与运行日志。</p>
      </div>
      <div class="dv-actions">
        <span v-if="matrix?.note" class="dv-note">{{ matrix.note }}</span>
        <span v-if="hasRows" class="dv-summary">{{ envs.length }} 个环境 · {{ runningCount }} 个运行中</span>
        <button type="button" class="dv-refresh" :disabled="loading" @click="load">
          <el-icon :class="{ spinning: loading }"><Refresh /></el-icon>
          {{ loading ? '刷新中' : '刷新' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="dv-error">{{ error }}</p>

    <div v-if="!hasRows && !loading" class="dv-empty">
      <h3>还没有可管理的部署</h3>
      <p>在流水线里完成一次 dev / test / prod 部署后，版本会出现在这里，并可按环境一键启停。</p>
    </div>

    <div v-if="hasRows" class="dv-grid">
      <section v-for="env in envs" :key="env.key" class="dv-env">
        <header class="dv-env-head">
          <div class="dv-env-name">
            <span>{{ envShort(env.key) }}</span>
            <code>{{ env.key }}</code>
          </div>
          <span class="dv-env-count">
            {{ cellsOfEnv(env.key).length }} 个版本 · {{ runningOfEnv(env.key) }} 运行中
          </span>
        </header>

        <div class="dv-cards">
          <article
            v-for="cell in cellsOfEnv(env.key)"
            :key="keyOf(cell)"
            class="dv-card"
            :class="[cell.state.toLowerCase(), { current: cell.current, active: keyOf(cell) === selectedKey }]"
            role="button"
            tabindex="0"
            @click="openDetail(cell)"
            @keydown.enter.prevent="openDetail(cell)"
          >
            <div class="dv-card-head">
              <div class="dv-version">
                <code :title="cell.version">{{ shortVersion(cell.version) }}</code>
                <span v-if="cell.current" class="dv-pill">当前</span>
              </div>
              <span class="dv-state" :class="cell.state.toLowerCase()">
                <i class="dv-dot"></i>{{ stateLabel(cell) }}
              </span>
            </div>

            <div class="dv-chips">
              <span class="dv-chip target">{{ cell.target }}</span>
              <span v-if="cell.server" class="dv-chip">{{ cell.server }}</span>
              <span v-if="cell.ports" class="dv-chip mono">{{ cell.ports }}</span>
              <span v-if="cell.health" class="dv-chip">health · {{ cell.health }}</span>
              <span v-if="cell.containers.length > 1" class="dv-chip">{{ cell.containers.length }} 容器</span>
            </div>

            <div class="dv-card-foot">
              <span class="dv-time" :title="formatDateTime(cell.deployedAt)">
                {{ cell.deployedAt ? `${relativeTime(cell.deployedAt)}部署` : '—' }}
              </span>
              <div class="dv-card-actions">
                <a
                  v-if="cell.url && cell.running"
                  class="dv-link"
                  :href="cell.url"
                  target="_blank"
                  rel="noreferrer"
                  @click.stop
                >访问 ↗</a>
                <button
                  type="button"
                  class="dv-btn"
                  :class="cell.running ? 'danger' : 'primary'"
                  :disabled="!!acting"
                  @click.stop="toggle(cell)"
                >
                  {{ acting === keyOf(cell) ? '处理中…' : cell.running ? '停止' : '启动' }}
                </button>
              </div>
            </div>
          </article>

          <p v-if="!cellsOfEnv(env.key).length" class="dv-env-empty">该环境还没有部署产物</p>
        </div>
      </section>
    </div>

    <!-- 最近保留的镜像：来自镜像版本账本（保留策略内），可直接按提交重新构建 -->
    <section v-if="retainedImages.length" class="dv-images">
      <header class="dv-images-head">
        <h3>最近保留的镜像</h3>
        <p>保留策略内的版本（当前/上一版本 + 最近 5 个）；点「重新构建」按该提交再跑一次流水线。</p>
      </header>
      <div class="dv-image-list">
        <article v-for="img in retainedImages" :key="img.tag" class="dv-image-card">
          <div class="dv-image-main">
            <code class="dv-image-tag" :title="img.tag">{{ img.tag }}</code>
            <span v-if="img.version" class="dv-chip">{{ img.version }}</span>
            <span v-if="img.gitSha" class="dv-chip mono">{{ img.gitSha.slice(0, 7) }}</span>
            <span class="dv-chip">{{ sizeLabel(img.sizeBytes) }}</span>
          </div>
          <div class="dv-image-foot">
            <span class="dv-time" :title="formatDateTime(img.deployedAt)">
              {{ img.deployedAt ? `${relativeTime(img.deployedAt)}部署` : '未部署' }}
            </span>
            <button
              type="button"
              class="dv-btn primary"
              :disabled="building === img.tag"
              @click.stop="rebuild(img)"
            >
              {{ building === img.tag ? '构建中…' : '重新构建' }}
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- 详情面板：完整部署信息 + 启停 / 重启 + 运行日志 -->
    <Teleport to="body">
      <div v-if="drawerOpen && selectedCell" class="dw-overlay" @click.self="drawerOpen = false">
        <aside class="dw-panel" role="dialog" aria-modal="true" aria-label="部署详情">
          <div class="dw">
            <header class="dw-head">
              <div class="dw-head-main">
                <div class="dw-env-line">
                  <span class="dw-env">{{ envShort(selectedCell.env) }}</span>
                  <code>{{ selectedCell.env }}</code>
                  <span class="dv-state" :class="selectedCell.state.toLowerCase()">
                    <i class="dv-dot"></i>{{ stateLabel(selectedCell) }}
                  </span>
                  <span v-if="selectedCell.current" class="dv-pill">当前版本</span>
                </div>
                <h3 class="dw-version" :title="selectedCell.version">{{ selectedCell.version }}</h3>
              </div>
              <button type="button" class="dw-close" aria-label="关闭" @click="drawerOpen = false">✕</button>
            </header>

            <div class="dw-actions">
              <a
                v-if="selectedCell.url"
                class="dw-open"
                :href="selectedCell.url"
                target="_blank"
                rel="noreferrer"
              >打开部署地址 ↗</a>
              <span v-else class="dw-open disabled">暂无访问地址</span>
              <div class="dw-action-btns">
                <button
                  v-if="!selectedCell.running"
                  type="button"
                  class="dw-btn primary"
                  :disabled="!!acting"
                  @click="toggle(selectedCell)"
                >{{ acting === keyOf(selectedCell) ? '启动中…' : '启动' }}</button>
                <template v-else>
                  <button
                    type="button"
                    class="dw-btn"
                    :disabled="!!acting"
                    @click="restart(selectedCell)"
                  >{{ acting === keyOf(selectedCell) ? '重启中…' : '重启' }}</button>
                  <button
                    type="button"
                    class="dw-btn danger"
                    :disabled="!!acting"
                    @click="toggle(selectedCell)"
                  >停止</button>
                </template>
              </div>
            </div>

            <dl class="dw-info">
              <div>
                <dt>部署目标</dt>
                <dd>{{ selectedCell.target }}</dd>
              </div>
              <div v-if="selectedCell.server">
                <dt>服务器</dt>
                <dd>{{ selectedCell.server }}</dd>
              </div>
              <div>
                <dt>版本号</dt>
                <dd><code>{{ selectedCell.version || '—' }}</code></dd>
              </div>
              <div>
                <dt>发布提交</dt>
                <dd><code>{{ selectedCell.release || '—' }}</code></dd>
              </div>
              <div>
                <dt>访问端口</dt>
                <dd class="mono">{{ selectedCell.ports || '—' }}</dd>
              </div>
              <div>
                <dt>HOST 进程</dt>
                <dd class="mono">{{ selectedCell.pid > 0 ? `pid ${selectedCell.pid}` : '—' }}</dd>
              </div>
              <div>
                <dt>健康状态</dt>
                <dd>{{ selectedCell.health || '—' }}</dd>
              </div>
              <div>
                <dt>部署时间</dt>
                <dd>{{ formatDateTime(selectedCell.deployedAt) }}</dd>
              </div>
              <div class="full">
                <dt>镜像</dt>
                <dd class="mono ellipsis">{{ selectedCell.image || '—' }}</dd>
              </div>
              <div class="full">
                <dt>容器</dt>
                <dd class="mono">{{ selectedCell.containers.length ? selectedCell.containers.join('、') : '—' }}</dd>
              </div>
              <div class="full">
                <dt>说明</dt>
                <dd>{{ selectedCell.detail || '—' }}</dd>
              </div>
            </dl>

            <section class="dw-logs">
              <header class="dw-logs-head">
                <div class="dw-logs-title">
                  运行日志
                  <span class="dw-logs-src">{{ logSourceLabel() }}</span>
                </div>
                <div class="dw-logs-tools">
                  <select
                    v-if="logContainers.length > 1"
                    v-model="logContainer"
                    class="dw-select"
                    @change="onContainerChange"
                  >
                    <option v-for="name in logContainers" :key="name" :value="name">{{ name }}</option>
                  </select>
                  <label class="dw-auto">
                    <input v-model="logAuto" type="checkbox" />跟随
                  </label>
                  <button type="button" class="dw-btn" :disabled="logLoading" @click="loadLogs()">
                    {{ logLoading ? '加载中…' : '刷新' }}
                  </button>
                </div>
              </header>

              <div ref="logBox" class="dw-log-box">
                <p v-if="logError" class="dw-log-hint error">{{ logError }}</p>
                <p v-else-if="logLoading && !logLines.length" class="dw-log-hint">日志加载中…</p>
                <p v-else-if="!logLines.length" class="dw-log-hint">
                  暂无日志{{ selectedCell.running ? '' : '（应用未运行）' }}
                </p>
                <pre v-else class="dw-log">{{ logLines.join('\n') }}</pre>
              </div>
              <p v-if="logNote" class="dw-log-note">{{ logNote }}</p>
            </section>
          </div>
        </aside>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.dv {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 8px clamp(16px, 2vw, 32px) 24px;
  overflow: hidden;
}
.dv-head {
  display: flex;
  flex: none;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}
.dv-title h2 {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--ink);
}
.dv-title p {
  margin: 4px 0 0;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.dv-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}
.dv-note {
  font-size: var(--text-2xs);
  color: var(--warn-ink);
}
.dv-summary {
  font-size: var(--text-2xs);
  color: var(--ink-muted);
}
.dv-refresh {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: var(--text-xs);
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  transition: background var(--dur-fast) var(--ease-out);
}
.dv-refresh:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.dv-refresh:disabled {
  cursor: default;
  opacity: 0.6;
}
.spinning {
  animation: dv-spin 1s linear infinite;
}
@keyframes dv-spin {
  to { transform: rotate(360deg); }
}
.dv-error {
  margin: 0;
  padding: 8px 12px;
  font-size: var(--text-xs);
  color: var(--danger-ink);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
}
.dv-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 240px;
  color: var(--ink-muted);
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--radius-lg);
}
.dv-empty h3 {
  margin: 0;
  font-size: var(--text-base);
  color: var(--ink-soft);
}
.dv-empty p {
  max-width: 420px;
  margin: 0;
  font-size: var(--text-xs);
  line-height: var(--leading-relaxed);
}

/* ---- 环境列 ---- */
.dv-grid {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  min-height: 0;
  padding-bottom: 4px;
  overflow: auto;
  align-content: start;
}
.dv-env {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.dv-env-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 0 2px 8px;
  border-bottom: 1px solid var(--line-soft);
}
.dv-env-name {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink);
}
.dv-env-name code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  color: var(--ink-faint);
  text-transform: uppercase;
}
.dv-env-count {
  font-size: var(--text-2xs);
  color: var(--ink-muted);
}
.dv-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ---- 部署卡片 ---- */
.dv-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-1);
  outline: none;
  transition:
    border-color var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out),
    transform var(--dur-fast) var(--ease-out);
}
.dv-card:hover,
.dv-card:focus-visible {
  border-color: var(--line);
  box-shadow: var(--shadow-2);
  transform: translateY(-1px);
}
.dv-card.active {
  border-color: var(--accent);
  box-shadow: var(--shadow-focus);
}
.dv-card.running {
  border-left-color: var(--ok);
}
.dv-card.stopped {
  border-left-color: var(--ink-faint);
}
.dv-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dv-version {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dv-version code {
  overflow: hidden;
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dv-pill {
  flex: none;
  padding: 0 6px;
  font-size: var(--text-2xs);
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.dv-state {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 5px;
  font-size: var(--text-2xs);
  color: var(--ink-muted);
}
.dv-state.running {
  color: var(--ok-ink);
}
.dv-dot {
  width: 6px;
  height: 6px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.dv-state.running .dv-dot {
  background: var(--ok);
  box-shadow: 0 0 0 3px var(--ok-soft);
}
.dv-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dv-chip {
  padding: 1px 6px;
  font-size: var(--text-2xs);
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.dv-chip.mono {
  font-family: var(--font-mono);
}
.dv-chip.target {
  color: var(--ink-soft);
  font-weight: 600;
  letter-spacing: 0.02em;
}
.dv-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 2px;
}
.dv-time {
  overflow: hidden;
  font-size: var(--text-2xs);
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dv-card-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}
.dv-link {
  font-size: var(--text-xs);
  color: var(--accent-ink);
  text-decoration: none;
}
.dv-link:hover {
  text-decoration: underline;
}
.dv-btn {
  padding: 3px 10px;
  font-size: var(--text-xs);
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  transition: background var(--dur-fast) var(--ease-out);
}
.dv-btn:hover:not(:disabled) {
  background: var(--surface-hover);
}
.dv-btn.primary {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.dv-btn.danger {
  color: var(--danger-ink);
  background: var(--danger-soft);
  border-color: transparent;
}
.dv-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.dv-env-empty {
  margin: 0;
  padding: 20px 12px;
  font-size: var(--text-xs);
  color: var(--ink-faint);
  text-align: center;
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-md);
}

/* ---- 详情面板 ---- */
.dw-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  justify-content: flex-end;
  background: rgb(15 23 42 / 30%);
}
.dw-panel {
  display: flex;
  flex-direction: column;
  width: min(560px, 100%);
  height: 100%;
  overflow-y: auto;
  background: var(--panel);
  border-left: 1px solid var(--line);
  box-shadow: var(--shadow-3);
}
.dw {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 20px 24px;
}
.dw-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dw-head-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.dw-env-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.dw-env {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--ink-soft);
}
.dw-env-line code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  color: var(--ink-faint);
  text-transform: uppercase;
}
.dw-version {
  margin: 0;
  overflow: hidden;
  font-family: var(--font-mono);
  font-size: var(--text-xl);
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dw-close {
  flex: none;
  width: 26px;
  height: 26px;
  font-size: var(--text-sm);
  color: var(--ink-muted);
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.dw-close:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.dw-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.dw-open {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--accent-ink);
  text-decoration: none;
}
.dw-open:hover {
  text-decoration: underline;
}
.dw-open.disabled {
  font-weight: 400;
  color: var(--ink-faint);
}
.dw-action-btns {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dw-btn {
  padding: 4px 12px;
  font-size: var(--text-xs);
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.dw-btn:hover:not(:disabled) {
  background: var(--surface-hover);
}
.dw-btn.primary {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.dw-btn.danger {
  color: var(--danger-ink);
  background: var(--danger-soft);
  border-color: transparent;
}
.dw-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.dw-info {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  overflow: hidden;
  background: var(--line-soft);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.dw-info > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  background: var(--panel);
}
.dw-info > div.full {
  grid-column: 1 / -1;
}
.dw-info dt {
  font-size: var(--text-2xs);
  color: var(--ink-muted);
}
.dw-info dd {
  margin: 0;
  overflow: hidden;
  font-size: var(--text-xs);
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dw-info dd.mono {
  font-family: var(--font-mono);
}
.dw-info dd.ellipsis {
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dw-info code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}

/* ---- 日志面板 ---- */
.dw-logs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}
.dw-logs-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dw-logs-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--ink-soft);
}
.dw-logs-src {
  padding: 0 6px;
  font-size: var(--text-2xs);
  font-weight: 500;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.dw-logs-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dw-select {
  max-width: 200px;
  padding: 3px 6px;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  color: var(--ink-soft);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.dw-auto {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-2xs);
  color: var(--ink-muted);
  cursor: pointer;
}
.dw-log-box {
  max-height: 360px;
  min-height: 140px;
  overflow: auto;
  background: var(--code-bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.dw-log {
  margin: 0;
  padding: 10px 12px;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
}
.dw-log-hint {
  margin: 0;
  padding: 24px 12px;
  font-size: var(--text-xs);
  color: var(--ink-muted);
  text-align: center;
}
.dw-log-hint.error {
  color: var(--danger-ink);
}
.dw-log-note {
  margin: 0;
  font-size: var(--text-2xs);
  color: var(--ink-faint);
}

@media (max-width: 900px) {
  .dv-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .dv-actions {
    justify-content: flex-start;
  }
  .dw-info {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---- 最近保留的镜像 ---- */
.dv-images {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.dv-images-head {
  margin-bottom: 10px;
}
.dv-images-head h3 {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--ink);
}
.dv-images-head p {
  margin: 4px 0 0;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.dv-image-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 10px;
}
.dv-image-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}
.dv-image-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.dv-image-tag {
  max-width: 200px;
  overflow: hidden;
  font-size: var(--text-xs);
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dv-image-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
</style>
