<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getDeploymentMatrix,
  startDeployment,
  stopDeployment,
  type DeploymentCell,
  type DeploymentMatrix,
} from '../../api/workspace'

/**
 * 环境矩阵：行 = 部署版本（流水线产出的镜像/产物），列 = dev / test / prod。
 *
 * <p>只管理项目应用本身（前后端容器 / HOST jar 进程），不展示中间件；
 * 启停直接作用于已存在的产物，不会重新构建或重新部署。
 */
const props = defineProps<{ projectId: string }>()

const matrix = ref<DeploymentMatrix | null>(null)
const loading = ref(false)
const error = ref('')
const acting = ref('')
let timer: ReturnType<typeof setInterval> | null = null

const cells = computed(() => {
  const map: Record<string, DeploymentCell> = {}
  for (const cell of matrix.value?.cells ?? []) {
    map[`${cell.version}|${cell.env}`] = cell
  }
  return map
})

const envs = computed(() => matrix.value?.environments ?? [])
const versions = computed(() => matrix.value?.versions ?? [])
const hasRows = computed(() => versions.value.length > 0)

function cellOf(version: string, env: string): DeploymentCell | null {
  return cells.value[`${version}|${env}`] ?? null
}

function shortVersion(version: string): string {
  if (!version || version === 'latest') return 'latest'
  return version.length > 16 ? `${version.slice(0, 15)}…` : version
}

function relativeTime(at: number): string {
  if (!at) return ''
  const diff = Date.now() - at
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

function stateLabel(cell: DeploymentCell): string {
  if (cell.state === 'RUNNING') return '运行中'
  if (cell.state === 'STOPPED') return '已停止'
  return cell.state
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

async function toggle(cell: DeploymentCell): Promise<void> {
  const key = `${cell.version}|${cell.env}`
  if (acting.value) return
  acting.value = key
  try {
    const result = cell.running
      ? await stopDeployment(props.projectId, cell.env, cell.version)
      : await startDeployment(props.projectId, cell.env, cell.version)
    ElMessage.success(result.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    acting.value = ''
  }
}

onMounted(() => {
  void load()
  // 部署状态可能被流水线改变：停留在这个 Tab 时轻量轮询
  timer = setInterval(() => {
    if (!acting.value) void load()
  }, 10_000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <section class="env-view">
    <header class="env-head">
      <div class="env-title">
        <h2>环境与版本</h2>
        <p>流水线部署成功的前后端镜像 / 产物，按环境独立启停；不包含中间件。</p>
      </div>
      <div class="env-actions">
        <span v-if="matrix?.note" class="env-note">{{ matrix.note }}</span>
        <button type="button" class="text-btn" :disabled="loading" @click="load">
          <el-icon :class="{ spinning: loading }"><Refresh /></el-icon>
          {{ loading ? '刷新中' : '刷新' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="env-error">{{ error }}</p>

    <div v-if="!hasRows && !loading" class="env-empty">
      <h3>还没有可管理的部署</h3>
      <p>在流水线里完成一次 dev / test / prod 部署后，版本会出现在这里，并可按环境一键启停。</p>
    </div>

    <div v-else class="matrix-scroll">
      <div class="matrix" :style="{ '--env-count': envs.length }">
        <div class="matrix-row matrix-head">
          <div class="version-head">版本</div>
          <div v-for="env in envs" :key="env.key" class="env-head-cell">
            <span class="env-name">{{ env.label }}</span>
            <code class="env-key">{{ env.key }}</code>
          </div>
        </div>

        <div v-for="version in versions" :key="version" class="matrix-row">
          <div class="version-cell">
            <code class="version-code" :title="version">{{ shortVersion(version) }}</code>
            <span
              v-if="Object.values(cells).some((c) => c.version === version && c.current)"
              class="current-tag"
            >当前</span>
          </div>
          <div v-for="env in envs" :key="env.key" class="cell-slot">
            <template v-if="cellOf(version, env.key)">
              <article class="env-cell" :class="cellOf(version, env.key)!.state.toLowerCase()">
                <div class="cell-top">
                  <span class="state-badge">
                    <i class="state-dot"></i>{{ stateLabel(cellOf(version, env.key)!) }}
                  </span>
                  <span class="target-tag">{{ cellOf(version, env.key)!.target }}</span>
                </div>
                <div class="cell-meta">
                  <span v-if="cellOf(version, env.key)!.ports" class="meta-line">
                    {{ cellOf(version, env.key)!.ports }}
                  </span>
                  <span v-if="cellOf(version, env.key)!.health" class="meta-line">
                    health: {{ cellOf(version, env.key)!.health }}
                  </span>
                  <span v-if="cellOf(version, env.key)!.containers.length" class="meta-line ellipsis">
                    {{ cellOf(version, env.key)!.containers.join('、') }}
                  </span>
                  <span v-if="cellOf(version, env.key)!.image" class="meta-line ellipsis">
                    {{ cellOf(version, env.key)!.image }}
                  </span>
                  <span v-if="cellOf(version, env.key)!.deployedAt" class="meta-line faint">
                    {{ relativeTime(cellOf(version, env.key)!.deployedAt) }}部署
                  </span>
                </div>
                <div class="cell-actions">
                  <button
                    type="button"
                    class="cell-btn"
                    :class="cellOf(version, env.key)!.running ? 'stop' : 'start'"
                    :disabled="!!acting"
                    @click="toggle(cellOf(version, env.key)!)"
                  >
                    {{ acting === `${version}|${env.key}` ? '处理中…'
                      : cellOf(version, env.key)!.running ? '停止' : '启动' }}
                  </button>
                  <a
                    v-if="cellOf(version, env.key)!.url && cellOf(version, env.key)!.running"
                    class="cell-link"
                    :href="cellOf(version, env.key)!.url!"
                    target="_blank"
                    rel="noreferrer"
                  >打开</a>
                </div>
              </article>
            </template>
            <div v-else class="empty-cell">未部署</div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.env-view {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 8px clamp(16px, 2vw, 32px) 24px;
  overflow: hidden;
}
.env-head {
  display: flex;
  flex: none;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}
.env-title h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.env-title p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ink-muted);
}
.env-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.env-note {
  font-size: 11px;
  color: var(--warn-ink);
}
.text-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.text-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.text-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.spinning {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.env-error {
  margin: 0;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--danger-ink);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
}
.env-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 240px;
  color: var(--ink-muted);
  text-align: center;
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-lg);
}
.env-empty h3 {
  margin: 0;
  font-size: 14px;
  color: var(--ink-soft);
}
.env-empty p {
  max-width: 420px;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}
.matrix-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.matrix {
  min-width: calc(150px + var(--env-count) * 240px);
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}
.matrix-row {
  display: grid;
  grid-template-columns: 150px repeat(var(--env-count), minmax(240px, 1fr));
  border-top: 1px solid var(--line-soft);
}
.matrix-row:first-child {
  border-top: none;
}
.matrix-head {
  background: var(--surface-sunken);
}
.version-head,
.env-head-cell {
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
}
.env-head-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  border-left: 1px solid var(--line-soft);
}
.env-key {
  font-size: 10px;
  color: var(--ink-faint);
  text-transform: uppercase;
}
.version-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px;
}
.version-code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 12px;
  color: var(--ink);
}
.current-tag {
  padding: 0 6px;
  font-size: 10px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.cell-slot {
  padding: 10px;
  border-left: 1px solid var(--line-soft);
}
.env-cell {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-left-width: 3px;
  border-left-color: var(--line-soft);
  border-radius: var(--radius-md);
}
.env-cell.running {
  border-left-color: var(--ok);
}
.env-cell.stopped {
  border-left-color: var(--ink-faint);
}
.cell-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.state-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
}
.env-cell.running .state-badge {
  color: var(--ok-ink);
}
.state-dot {
  width: 6px;
  height: 6px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.env-cell.running .state-dot {
  background: var(--ok);
  box-shadow: 0 0 0 3px var(--ok-soft);
}
.env-cell.stopped .state-dot {
  background: var(--ink-faint);
}
.target-tag {
  padding: 0 5px;
  font-size: 10px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.cell-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--ink-muted);
}
.meta-line {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.meta-line.faint {
  color: var(--ink-faint);
}
.cell-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
}
.cell-btn {
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.cell-btn.start {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.cell-btn.stop {
  color: var(--danger-ink);
  background: var(--danger-soft);
  border-color: transparent;
}
.cell-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.cell-link {
  font-size: 12px;
  color: var(--accent-ink);
  text-decoration: none;
}
.cell-link:hover {
  text-decoration: underline;
}
.empty-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 96px;
  font-size: 12px;
  color: var(--ink-faint);
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-md);
}
@media (max-width: 900px) {
  .env-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
