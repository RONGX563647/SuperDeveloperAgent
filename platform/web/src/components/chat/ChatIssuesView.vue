<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Close, Delete, Download, Edit, Link, Picture, Plus, Refresh, RefreshRight,
  Search, Tickets,
} from '@element-plus/icons-vue'
import {
  addGitIssueComment, closeGitIssue, createGitIssue, deleteGitIssue, deleteGitIssueComment,
  exportGitIssues,
  getGitIssueVersions,
  listGitIssueComments, listGitIssues, reopenGitIssue, syncGitIssue, updateGitIssue,
  uploadIssueImage,
  type GitIssueCommentRow, type GitIssuePriority, type GitIssueRow,
} from '../../api/issues'
import MarkdownView from './MarkdownView.vue'
import { appStore } from '../../stores/app'
import { listProjects, type ProjectRow } from '../../api/project'
import {
  filterByVersion, versionFilterOptions,
} from '../../utils/taskVersionFilter'
import VisXYContainer from '@unovis/vue/containers/xy-container'
import VisSingleContainer from '@unovis/vue/containers/single-container'
import VisStackedBar, { VisStackedBarSelectors } from '@unovis/vue/components/stacked-bar'
import VisArea from '@unovis/vue/components/area'
import VisDonut, { VisDonutSelectors } from '@unovis/vue/components/donut'
import VisScatter, { VisScatterSelectors } from '@unovis/vue/components/scatter'
import VisAxis from '@unovis/vue/components/axis'
import VisTooltip from '@unovis/vue/components/tooltip'
import VisCrosshair from '@unovis/vue/components/crosshair'
import VisPlotline from '@unovis/vue/components/plotline'
import { CurveType } from '@unovis/ts/types/curve'

/**
 * 对话页「任务」Tab：项目托管的 Git Issue 双向同步任务。
 *
 * 采用单列列表：一行一个任务，点击行展开描述 / 评论 / 操作；新建与编辑
 * 直接以列表顶部的卡片形式出现，不使用悬浮弹窗。数据与 Agent 工具同源。
 */
const emit = defineEmits<{ (e: 'count', value: number): void }>()

const items = ref<GitIssueRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const stateFilter = ref('')
const kindFilter = ref('')
const priorityFilter = ref<GitIssuePriority | ''>('')
/** 版本号筛选：空串=全部；`__none__`=未关联；其它=版本号精确匹配。 */
const versionFilter = ref('')
const selectedId = ref('')
const comments = ref<GitIssueCommentRow[]>([])
const commentsLoading = ref(false)
const commentText = ref('')
const commenting = ref(false)
const actionBusy = ref('')

const stateOptions = [
  { label: '全部', value: '' },
  { label: '待处理', value: 'OPEN' },
  { label: '已完成', value: 'CLOSED' },
]

/** 任务分类：Bug（缺陷） / Feat（需求）。 */
const kindOptions = [
  { label: '全部类型', value: '' },
  { label: 'Bug', value: 'BUG' },
  { label: 'Feat', value: 'FEAT' },
]

/** 任务页视图：列表（默认）/ 量化看板。 */
const taskView = ref<'list' | 'board'>('list')

/** 优先级：P0 最高，P2 普通。 */
const PRIORITY_VALUES: GitIssuePriority[] = ['P0', 'P1', 'P2']
const PRIORITY_RANK: Record<string, number> = { P0: 0, P1: 1, P2: 2 }
const priorityTabs: { label: string; value: GitIssuePriority | '' }[] = [
  { label: '全部', value: '' },
  { label: 'P0', value: 'P0' },
  { label: 'P1', value: 'P1' },
  { label: 'P2', value: 'P2' },
]

/** 列表排序：默认最近更新优先；任务多时按状态/标题更好找。 */
const sortBy = ref<'updated' | 'created' | 'title' | 'priority'>('updated')

/** 分类、优先级、版本与排序在前端处理：数据量小，切换无需再请求。 */
const visibleItems = computed(() => {
  let rows = [...items.value]
  if (kindFilter.value) rows = rows.filter((i) => (i.kind ?? 'BUG') === kindFilter.value)
  if (priorityFilter.value) {
    rows = rows.filter((i) => (i.priority ?? 'P2') === priorityFilter.value)
  }
  rows = filterByVersion(rows, versionFilter.value)
  return rows.sort((a, b) => {
    if (sortBy.value === 'priority') {
      const diff = (PRIORITY_RANK[a.priority ?? 'P2'] ?? 2)
        - (PRIORITY_RANK[b.priority ?? 'P2'] ?? 2)
      if (diff) return diff
      return b.updatedAt - a.updatedAt
    }
    if (sortBy.value === 'title') return a.title.localeCompare(b.title, 'zh-CN')
    if (sortBy.value === 'created') return b.createdAt - a.createdAt
    return b.updatedAt - a.updatedAt
  })
})

/** 版本筛选项基于**全部已加载任务**，不随其它筛选变化：否则选完就消失、无法回退。 */
const versionOptionsForFilter = computed(() => versionFilterOptions(items.value))

/**
 * 生效中的筛选条件。
 *
 * <p>有了这排 chips，用户不用回头逐个数「刚才到底勾了什么」就能看出当前视图是怎么收窄的，
 * 并且能只撤掉某一条——这比一排无标签小按钮靠反复试更好用。
 */
const activeFilters = computed(() => {
  const list: { key: string; label: string; clear: () => void }[] = []
  if (keyword.value.trim()) {
    const text = keyword.value.trim()
    list.push({
      key: 'keyword',
      label: `搜索：${text.length > 12 ? `${text.slice(0, 12)}…` : text}`,
      clear: () => { keyword.value = '' },
    })
  }
  if (stateFilter.value) {
    const label = stateOptions.find((o) => o.value === stateFilter.value)?.label ?? stateFilter.value
    list.push({ key: 'state', label: `状态：${label}`, clear: () => { stateFilter.value = '' } })
  }
  if (kindFilter.value) {
    const label = kindOptions.find((o) => o.value === kindFilter.value)?.label ?? kindFilter.value
    list.push({ key: 'kind', label: `类型：${label}`, clear: () => { kindFilter.value = '' } })
  }
  if (priorityFilter.value) {
    list.push({
      key: 'priority', label: `优先级：${priorityFilter.value}`,
      clear: () => { priorityFilter.value = '' },
    })
  }
  if (versionFilter.value) {
    const label = versionOptionsForFilter.value
      .find((o) => o.value === versionFilter.value)?.label ?? versionFilter.value
    list.push({
      key: 'version', label: `版本：${label}`,
      clear: () => { versionFilter.value = '' },
    })
  }
  return list
})

/** 一键回到全部（排序不属于筛选，保持用户选择）。 */
function resetFilters(): void {
  keyword.value = ''
  stateFilter.value = ''
  kindFilter.value = ''
  priorityFilter.value = ''
  versionFilter.value = ''
}

/** 导出中：按钮 loading，避免连点重复下载。 */
const exporting = ref(false)

/** 导出文件名：带时间戳，多次导出不会互相覆盖。 */
function exportFileName(now = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}`
  return `tasks-${stamp}.xlsx`
}

/**
 * 导出**当前筛选出的**任务为 Excel。
 *
 * <p>只把 id 交给后端：界面上的过滤（类型/优先级/版本/排序）都在前端，后端若按自己的一套
 * 规则重算，导出结果迟早和界面对不上。
 */
async function exportExcel(): Promise<void> {
  const ids = visibleItems.value.map((row) => row.id)
  if (!ids.length) {
    ElMessage.warning('当前没有可导出的任务')
    return
  }
  exporting.value = true
  try {
    const blob = await exportGitIssues(ids)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = exportFileName()
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    // 延后释放：立刻 revoke 会让部分浏览器拿不到数据
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    ElMessage.success(`已导出 ${ids.length} 条任务`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

/** 看板量化：完成率、优先级分布、类型构成。 */
const stats = computed(() => {
  const rows = items.value
  const total = rows.length
  const open = rows.filter((r) => r.state === 'OPEN').length
  const closed = total - open
  const countOf = (p: GitIssuePriority) => rows.filter((r) => (r.priority ?? 'P2') === p).length
  const p0 = countOf('P0')
  const p1 = countOf('P1')
  const p2 = Math.max(0, total - p0 - p1)
  const bug = rows.filter((r) => (r.kind ?? 'BUG') === 'BUG').length
  const feat = total - bug
  const percent = total ? Math.round((closed / total) * 100) : 0
  return { total, open, closed, p0, p1, p2, bug, feat, percent }
})

/** 看板时间范围：7 天 / 30 天。 */
const chartRange = ref<7 | 30>(7)

interface ChartBucket {
  index: number
  label: string
  start: number
  bug: number
  feat: number
  done: number
}

/** 按天分桶：新增（Bug/Feat）与完成数，供堆叠柱 / 面积图。 */
const chartBuckets = computed<ChartBucket[]>(() => {
  const days = chartRange.value
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const buckets: ChartBucket[] = []
  for (let i = days - 1; i >= 0; i -= 1) {
    const start = today.getTime() - i * 86_400_000
    const date = new Date(start)
    buckets.push({
      index: days - 1 - i,
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      start, bug: 0, feat: 0, done: 0,
    })
  }
  const bucketOf = (ts: number) => buckets.find((b) => ts >= b.start && ts < b.start + 86_400_000)
  for (const row of items.value) {
    const created = bucketOf(row.createdAt)
    if (created) {
      if ((row.kind ?? 'BUG') === 'BUG') created.bug += 1
      else created.feat += 1
    }
    if (row.state === 'CLOSED') {
      const doneBucket = bucketOf(row.updatedAt)
      if (doneBucket) doneBucket.done += 1
    }
  }
  return buckets
})

/** X 轴刻度：7 天全展示，30 天每 5 天一个。 */
const chartTicks = computed(() => {
  const buckets = chartBuckets.value
  const step = chartRange.value === 7 ? 1 : 5
  return buckets
    .filter((_, i) => i % step === 0 || i === buckets.length - 1)
    .map((b) => b.index)
})
const chartTickFormat = (tick: number): string =>
  chartBuckets.value[Math.round(tick)]?.label ?? ''

/** 当前时间范围内的汇总，用于图表标题。 */
const chartTotals = computed(() => ({
  created: chartBuckets.value.reduce((n, b) => n + b.bug + b.feat, 0),
  bug: chartBuckets.value.reduce((n, b) => n + b.bug, 0),
  feat: chartBuckets.value.reduce((n, b) => n + b.feat, 0),
  done: chartBuckets.value.reduce((n, b) => n + b.done, 0),
}))

/** 近 7 天完成数与新增 Bug 数，用于列表页数字条。 */
function lastWeekStart(): number {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return today.getTime() - 6 * 86_400_000
}
const weeklyDone = computed(() => {
  const start = lastWeekStart()
  return items.value.filter((i) => i.state === 'CLOSED' && i.updatedAt >= start).length
})
const weeklyBugs = computed(() => {
  const start = lastWeekStart()
  return items.value
    .filter((i) => (i.kind ?? 'BUG') === 'BUG' && i.createdAt >= start).length
})
const avgCycleHours = computed(() => {
  const closed = items.value.filter((i) => i.state === 'CLOSED')
  if (!closed.length) return 0
  const total = closed.reduce((sum, i) => sum + Math.max(0, i.updatedAt - i.createdAt), 0)
  return Math.round((total / closed.length / 3_600_000) * 10) / 10
})

/** 未完成任务的优先级 × 类型矩阵。 */
const priorityMatrix = computed(() => PRIORITY_VALUES.map((key) => {
  const openRows = items.value.filter(
    (i) => i.state === 'OPEN' && (i.priority ?? 'P2') === key)
  return {
    key,
    bug: openRows.filter((i) => (i.kind ?? 'BUG') === 'BUG').length,
    feat: openRows.filter((i) => (i.kind ?? 'BUG') === 'FEAT').length,
    total: openRows.length,
  }
}))

/** 风险项：同步失败的任务（远端推送失败需要重试）。 */
const riskTasks = computed(() => items.value.filter((i) => i.syncStatus === 'FAILED').slice(0, 5))

/** 同步状态：已同步 / 仅本地 / 失败。 */
const syncStats = computed(() => ({
  synced: items.value.filter((i) => i.syncStatus === 'SYNCED').length,
  local: items.value.filter((i) => i.syncStatus !== 'SYNCED' && i.syncStatus !== 'FAILED').length,
  failed: items.value.filter((i) => i.syncStatus === 'FAILED').length,
}))

// ---------------------------------------------------------------- 看板图表数据

/** 图表访问器：Unovis 用函数取值，集中定义避免模板里重复创建。 */
const bucketX = (d: ChartBucket) => d.index
const trendY = [(d: ChartBucket) => d.bug, (d: ChartBucket) => d.feat]
const doneY = (d: ChartBucket) => d.done

/** 类型构成环形图。 */
const donutData = computed(() => [
  { name: 'Bug', value: stats.value.bug, color: '#EF4444' },
  { name: 'Feat', value: stats.value.feat, color: '#8B5CF6' },
])
const donutValue = (d: { value: number }) => d.value
const donutColor = (d: { color: string }) => d.color

/** 四象限散点：横轴积压天数，纵轴优先级权重。 */
interface QuadrantPoint {
  id: string
  title: string
  priority: GitIssuePriority
  kind: string
  ageDays: number
  weight: number
  size: number
}
const quadrantPoints = computed<QuadrantPoint[]>(() => {
  const now = Date.now()
  return items.value
    .filter((i) => i.state === 'OPEN')
    .map((i) => {
      const priority = (i.priority ?? 'P2') as GitIssuePriority
      const weight = priority === 'P0' ? 3 : priority === 'P1' ? 2 : 1
      const ageDays = Math.max(0.1, Math.round(((now - i.createdAt) / 86_400_000) * 10) / 10)
      return {
        id: i.id, title: i.title, priority, kind: i.kind ?? 'BUG',
        ageDays, weight, size: 12 + weight * 3,
      }
    })
})
const quadMaxAge = computed(
  () => Math.max(2, ...quadrantPoints.value.map((p) => p.ageDays)) + 0.2)
const quadSplitX = computed(() => Math.round((quadMaxAge.value / 2) * 10) / 10)
const quadX = (d: QuadrantPoint) => d.ageDays
const quadY = (d: QuadrantPoint) => d.weight
const quadSize = (d: QuadrantPoint) => d.size
const quadColor = (d: QuadrantPoint) => (d.kind === 'BUG' ? '#EF4444' : '#8B5CF6')
const priorityTick = (tick: number): string =>
  tick === 3 ? 'P0' : tick === 2 ? 'P1' : tick === 1 ? 'P2' : ''
const quadAgeTick = (tick: number): string => `${Math.round(tick)}d`

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[ch] as string))
}

/** 柱状图 hover：StackedBar 绑定的是包装对象，需要取 datum。 */
interface StackedBarRecord {
  datum: ChartBucket
  index: number
  stackIndex: number
}
const trendTriggers = {
  [VisStackedBarSelectors.bar]: (record: StackedBarRecord) => {
    const d = record.datum
    const total = d.bug + d.feat
    return `<div style="font-size:10px;color:#94a3b8">${d.label}</div>`
      + (total
        ? `<div style="margin-top:2px;font-weight:600">新增 ${total} 个</div>`
          + (d.bug ? `<div style="margin-top:2px;font-size:11px">Bug <b>${d.bug}</b></div>` : '')
          + (d.feat ? `<div style="margin-top:2px;font-size:11px">Feat <b>${d.feat}</b></div>` : '')
        : '<div style="margin-top:2px;color:#64748b">无新增任务</div>')
  },
}

/** 环形图 hover：Donut 绑定的是 { data } 包装对象。 */
const donutTriggers = {
  [VisDonutSelectors.segment]: (record: { data: { name: string; value: number; color: string } }) => {
    const d = record.data
    return `<div style="display:flex;align-items:center;gap:6px;font-size:11px">`
      + `<span style="width:6px;height:6px;border-radius:50%;background:${d.color}"></span>`
      + `<span style="color:#cbd5e1">${d.name}</span><b>${d.value}</b></div>`
  },
}

const quadrantTriggers = {
  [VisScatterSelectors.point]: (record: QuadrantPoint | { datum: QuadrantPoint }) => {
    const d = 'datum' in record ? record.datum : record
    return `<div style="max-width:220px;font-size:12px;font-weight:600;white-space:normal">${escapeHtml(d.title)}</div>`
      + `<div style="margin-top:3px;font-size:11px;color:#94a3b8">`
      + `${d.kind === 'BUG' ? 'Bug' : 'Feat'} · ${d.priority} · 积压 ${d.ageDays} 天</div>`
  },
}

/** 面积图十字线提示。 */
function throughputTemplate(d: ChartBucket): string {
  return `<div style="font-size:10px;color:#94a3b8">${d.label}</div>`
    + `<div style="margin-top:2px;font-size:11px">完成 <b>${d.done}</b> 个任务</div>`
}

/** 看板「优先处理」：未关闭任务按优先级取前 6 条。 */
const focusTasks = computed(() => items.value
  .filter((i) => i.state === 'OPEN')
  .sort((a, b) => (PRIORITY_RANK[a.priority ?? 'P2'] ?? 2)
    - (PRIORITY_RANK[b.priority ?? 'P2'] ?? 2) || b.updatedAt - a.updatedAt)
  .slice(0, 6))

/** 看板点优先级：带筛选切回列表，形成「看板 → 明细」闭环。 */
function focusPriority(priority: GitIssuePriority | '') {
  priorityFilter.value = priority
  // 清掉版本筛选：否则看板统计（不分版本）点进来却是空列表
  versionFilter.value = ''
  taskView.value = 'list'
}

/** 看板点状态指标：按状态切回列表。 */
function focusState(state: '' | 'OPEN' | 'CLOSED') {
  stateFilter.value = state
  priorityFilter.value = ''
  versionFilter.value = ''
  taskView.value = 'list'
}

/** 看板点任务卡片：切回列表并选中详情。 */
async function openFromBoard(row: GitIssueRow): Promise<void> {
  taskView.value = 'list'
  selectedId.value = ''
  await select(row)
}

/** 概览并进页头：只保留两个关键数字，不再占一整块统计卡片。 */
const openCount = computed(() => items.value.filter((i) => i.state === 'OPEN').length)
const closedCount = computed(() => items.value.length - openCount.value)
/** 右侧详情当前选中的任务。 */
const selectedRow = computed(() => items.value.find((i) => i.id === selectedId.value) ?? null)

const PROVIDER_LABEL: Record<string, string> = {
  GITEE: 'Gitee', GITHUB: 'GitHub', GITLAB: 'GitLab',
}

const projects = ref<ProjectRow[]>([])
const currentProject = computed(
  () => projects.value.find((p) => p.id === appStore.projectId) ?? null)

/** 当前项目托管的仓库（通常只有一个），用于页头提示与新建任务同步目标。 */
const currentRepo = computed(() => {
  const url = currentProject.value?.repoUrl
  return { provider: providerOfUrl(url), repo: repoOfUrl(url) }
})

/** 已绑定仓库去重：只有一个仓库时行内只显示 #编号，避免每行重复仓库名。 */
const boundRepos = computed(() => [
  ...new Set(items.value.filter((i) => isBound(i)).map((i) => `${i.provider}/${i.repo}`)),
])
const singleRepo = computed(() => boundRepos.value.length <= 1)

let loadSeq = 0
let keywordTimer: ReturnType<typeof setTimeout> | undefined

function providerLabel(provider: string): string {
  return PROVIDER_LABEL[provider] ?? (provider || 'Git')
}

function providerOfUrl(url?: string | null): string {
  const text = (url ?? '').toLowerCase()
  if (text.includes('gitee')) return 'GITEE'
  if (text.includes('gitlab')) return 'GITLAB'
  if (text.includes('github')) return 'GITHUB'
  return ''
}

/** https://host/owner/repo(.git) 或 git@host:owner/repo.git → owner/repo */
function repoOfUrl(url?: string | null): string {
  if (!url) return ''
  return url
    .replace(/^git@([^:]+):/, 'https://$1/')
    .replace(/^https?:\/\/[^/]+\//, '')
    .replace(/\.git$/, '')
    .replace(/\/+$/, '')
}

function syncLabel(row: GitIssueRow): string {
  if (row.syncStatus === 'SYNCED') return '已同步'
  if (row.syncStatus === 'FAILED') return '同步失败'
  if (row.syncStatus === 'PENDING') return '同步中'
  return '仅本地'
}

function formatTime(ts?: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}

function labelList(labels: string): string[] {
  return (labels ?? '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
}

function kindLabel(kind?: string): string {
  return kind === 'FEAT' ? 'Feat' : 'Bug'
}

function isBound(row: GitIssueRow): boolean {
  return !!row.provider && !!row.repo && !!row.number
}

function remoteText(row: GitIssueRow): string {
  if (!isBound(row)) return '仅本地'
  return singleRepo.value ? `#${row.number}` : `${row.repo}#${row.number}`
}

async function load(silent = false): Promise<void> {
  const seq = ++loadSeq
  if (!silent) loading.value = true
  error.value = ''
  try {
    const rows = await listGitIssues({
      keyword: keyword.value.trim() || undefined,
      state: stateFilter.value || undefined,
    })
    if (seq !== loadSeq) return
    items.value = rows
    emit('count', rows.filter((r) => r.state === 'OPEN').length)
    if (!rows.some((r) => r.id === selectedId.value)) {
      selectedId.value = ''
    }
    if (pendingFocusId.value) {
      const target = rows.find((r) => r.id === pendingFocusId.value)
      pendingFocusId.value = ''
      if (target) {
        selectedId.value = ''
        void select(target)
      }
    }
  } catch (e) {
    if (seq === loadSeq) error.value = e instanceof Error ? e.message : '加载任务失败'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => void load(), 260)
})
watch(stateFilter, () => void load())
watch(kindFilter, () => {
  if (selectedId.value && !visibleItems.value.some((i) => i.id === selectedId.value)) {
    selectedId.value = ''
  }
})
watch(priorityFilter, () => {
  if (selectedId.value && !visibleItems.value.some((i) => i.id === selectedId.value)) {
    selectedId.value = ''
  }
})
watch(versionFilter, () => {
  if (selectedId.value && !visibleItems.value.some((i) => i.id === selectedId.value)) {
    selectedId.value = ''
  }
})
// 切换项目后重新取当前分支版本与标签候选
watch(() => appStore.projectId, (id) => {
  versionLoadedFor = ''
  if (id) void loadVersionContext(id)
})

onMounted(() => {
  void load()
  void listProjects().then((rows) => { projects.value = rows }).catch(() => {})
  void loadVersionContext(appStore.projectId)
})

// ---------------------------------------------------------------- 选中 / 评论

/** 左列选中一条任务，右列加载它的评论（已选中则不重复请求）。 */
async function select(row: GitIssueRow): Promise<void> {
  if (selectedId.value === row.id) return
  selectedId.value = row.id
  editorOpen.value = false
  commentText.value = ''
  await loadComments()
}

async function loadComments(): Promise<void> {
  if (!selectedId.value) {
    comments.value = []
    return
  }
  const id = selectedId.value
  commentsLoading.value = true
  try {
    const rows = await listGitIssueComments(id)
    if (selectedId.value === id) comments.value = rows
  } catch (e) {
    comments.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载评论失败')
  } finally {
    commentsLoading.value = false
  }
}

async function submitComment(): Promise<void> {
  const id = selectedId.value
  const text = commentText.value.trim()
  if (!id || !text) return
  commenting.value = true
  try {
    const comment = await addGitIssueComment(id, text)
    comments.value = [...comments.value, comment]
    commentText.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评论失败')
  } finally {
    commenting.value = false
  }
}

async function removeComment(comment: GitIssueCommentRow): Promise<void> {
  const id = selectedId.value
  if (!id || comment.source !== 'PLATFORM') return
  try {
    await deleteGitIssueComment(id, comment.id)
    comments.value = comments.value.filter((c) => c.id !== comment.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除评论失败')
  }
}

// ---------------------------------------------------------------- 新建 / 编辑

const editorOpen = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive({
  title: '',
  body: '',
  labels: '',
  kind: 'BUG' as 'BUG' | 'FEAT',
  priority: 'P2' as GitIssuePriority,
  version: '',
})

/** 关联版本号：候选来自项目现有 git 标签；默认值是当前分支版本线的下一个补丁版本。 */
const versionOptions = ref<string[]>([])
const versionSuggestion = ref('')
const versionBranch = ref('')
let versionLoadedFor = ''

/**
 * 版本上下文全部取自后端 `/git-issues/versions`。
 *
 * 不在前端再算一遍：版本推导规则（版本线取最大补丁 +1、其他分支取最新标签）
 * 一旦两边各写一份，任务栏显示的版本号就会和实际打 tag 的版本号对不上。
 */
async function loadVersionContext(projectId: string): Promise<void> {
  if (!projectId || versionLoadedFor === projectId) return
  versionLoadedFor = projectId
  try {
    const versions = await getGitIssueVersions(projectId)
    versionBranch.value = versions.branch
    versionOptions.value = versions.tags ?? []
    versionSuggestion.value = versions.suggested ?? ''
  } catch {
    versionBranch.value = ''
    versionOptions.value = []
    versionSuggestion.value = ''
  }
}

const editorTarget = computed(() => {
  const row = editingId.value
    ? items.value.find((i) => i.id === editingId.value)
    : undefined
  if (row && isBound(row)) {
    return { provider: row.provider, repo: row.repo }
  }
  return currentRepo.value
})

function openCreate(): void {
  taskView.value = 'list'
  editingId.value = ''
  Object.assign(form, {
    title: '', body: '', labels: '', kind: 'BUG', priority: 'P2',
    version: versionSuggestion.value,
  })
  // 首次打开时提示值可能还没加载完：加载后补上默认版本，不覆盖用户已输入的内容
  const projectId = appStore.projectId
  void loadVersionContext(projectId).then(() => {
    if (!editingId.value && !form.version) form.version = versionSuggestion.value
  })
  editorOpen.value = true
  selectedId.value = ''
}

function openEdit(row: GitIssueRow): void {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title, body: row.body, labels: row.labels, kind: row.kind ?? 'BUG',
    priority: row.priority ?? 'P2',
    version: row.version || versionSuggestion.value,
  })
  const projectId = row.projectId || appStore.projectId
  void loadVersionContext(projectId).then(() => {
    if (editingId.value === row.id && !row.version) {
      form.version = versionSuggestion.value
    }
  })
  editorOpen.value = true
}

function closeEditor(): void {
  editorOpen.value = false
  editingId.value = ''
}

// ---------------------------------------------------------------- 配图

/** 上传中标记：按钮与拖拽区共用，避免重复上传。 */
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function pickImage(): void {
  fileInput.value?.click()
}

/** 上传图片并把 Markdown 图片语法追加到正文/评论末尾。 */
async function insertImage(file: File, target: 'body' | 'comment'): Promise<void> {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('只支持图片文件')
    return
  }
  uploading.value = true
  try {
    const url = await uploadIssueImage(file)
    const alt = file.name.replace(/[[\]()]/g, '').trim() || '图片'
    const markdown = `\n![${alt}](${url})\n`
    if (target === 'body') {
      form.body = `${form.body.replace(/\s*$/, '')}${markdown}`
    } else {
      commentText.value = `${commentText.value.replace(/\s*$/, '')}${markdown}`
    }
    ElMessage.success('图片已插入')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '图片上传失败')
  } finally {
    uploading.value = false
  }
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void insertImage(file, 'body')
  input.value = ''
}

/** 粘贴图片：直接从剪贴板上传，不再需要先存文件。 */
function onPasteImage(event: ClipboardEvent, target: 'body' | 'comment'): void {
  const items = event.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (!item.type.startsWith('image/')) continue
    const file = item.getAsFile()
    if (!file) continue
    event.preventDefault()
    void insertImage(file, target)
    return
  }
}

/** 拖拽图片到输入区。 */
function onDropImage(event: DragEvent, target: 'body' | 'comment'): void {
  const file = event.dataTransfer?.files?.[0]
  if (!file || !file.type.startsWith('image/')) return
  event.preventDefault()
  void insertImage(file, target)
}

async function submit(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  saving.value = true
  try {
    const editingRow = editingId.value
      ? items.value.find((i) => i.id === editingId.value)
      : undefined
    const payload = {
      // 新建跟随当前项目；编辑保留原归属，避免把已绑定任务换到别的仓库
      projectId: editingRow ? editingRow.projectId : appStore.projectId,
      title: form.title.trim(),
      body: form.body,
      labels: form.labels.trim(),
      kind: form.kind,
      priority: form.priority,
      version: form.version.trim(),
      state: editingRow?.state ?? ('OPEN' as const),
    }
    const row = editingId.value
      ? await updateGitIssue(editingId.value, payload)
      : await createGitIssue(payload)
    if (row.syncStatus === 'FAILED') {
      ElMessage.warning(`任务已保存，但同步到 Git 失败：${row.syncError}`)
    } else {
      ElMessage.success(editingId.value ? '任务已更新' : '任务已创建')
    }
    closeEditor()
    await load(true)
    selectedId.value = row.id
    await loadComments()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存任务失败')
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 状态 / 同步 / 删除

function replaceRow(row: GitIssueRow): void {
  const index = items.value.findIndex((i) => i.id === row.id)
  if (index >= 0) items.value[index] = row
  emit('count', items.value.filter((i) => i.state === 'OPEN').length)
}

async function toggleState(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'state:' + row.id
  try {
    const next = row.state === 'OPEN'
      ? await closeGitIssue(row.id)
      : await reopenGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.warning(`本地状态已更新，同步 Git 失败：${next.syncError}`)
    } else {
      ElMessage.success(next.state === 'CLOSED' ? '任务已关闭' : '任务已重开')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新状态失败')
  } finally {
    actionBusy.value = ''
  }
}

async function resync(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'sync:' + row.id
  try {
    const next = await syncGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.error(`同步失败：${next.syncError}`)
    } else {
      ElMessage.success('已同步到 Git')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    actionBusy.value = ''
  }
}

async function removeIssue(row: GitIssueRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      isBound(row)
        ? `删除任务「${row.title}」？仅解除平台绑定，不会删除 Git 上的 Issue。`
        : `删除任务「${row.title}」？`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionBusy.value = 'delete:' + row.id
  try {
    await deleteGitIssue(row.id)
    items.value = items.value.filter((i) => i.id !== row.id)
    if (selectedId.value === row.id) selectedId.value = ''
    emit('count', items.value.filter((i) => i.state === 'OPEN').length)
    ElMessage.success('任务已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    actionBusy.value = ''
  }
}

// ---------------------------------------------------------------- 对外能力

/** 首次加载完成前收到的定位请求，等列表回来后再选中。 */
const pendingFocusId = ref('')

/** 工作区右栏点任务：切到列表视图并定位到这条工单。 */
async function focus(id: string): Promise<void> {
  taskView.value = 'list'
  const row = items.value.find((i) => i.id === id)
  if (row) {
    await select(row)
    return
  }
  pendingFocusId.value = id
}

defineExpose({ openCreate, focus })
</script>

<template>
  <div class="issues-view">
    <input
      ref="fileInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp"
      class="hidden-file" @change="onFileChange"
    />
    <div class="issues-shell">
      <header class="issues-head">
        <div class="issues-head-main">
          <div class="issues-title-row">
            <h1 class="issues-title">任务</h1>
            <div class="view-switch">
              <button
                type="button" class="view-switch-btn" :class="{ active: taskView === 'list' }"
                @click="taskView = 'list'"
              >列表</button>
              <button
                type="button" class="view-switch-btn" :class="{ active: taskView === 'board' }"
                @click="taskView = 'board'"
              >看板</button>
            </div>
            <span class="issues-sub">
              {{ openCount }} 待处理 · {{ closedCount }} 已完成 · Git 双向同步
            </span>
          </div>
          <p class="issues-desc">
            项目托管的 Git Issue 自动双向同步；Agent 可读取并操作同一批任务。
          </p>
        </div>
        <div class="issues-head-side">
          <span v-if="currentRepo.repo" class="repo-chip">
            <span class="repo-dot" />
            {{ providerLabel(currentRepo.provider) }}
            <span class="mono">{{ currentRepo.repo }}</span>
          </span>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon> 新建任务
          </el-button>
          <el-button size="small" :loading="loading" @click="load()">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </header>

      <!-- 量化看板：只在「看板」Tab 下展示 -->
      <section v-if="taskView === 'board'" class="board-view">
        <!-- 关键指标条 -->
        <div class="kpi-strip">
          <button type="button" class="stat-card" @click="focusState('CLOSED')">
            <span class="stat-head">
              <span>完成率</span>
              <i class="stat-icon blue">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21.2 15.9A10 10 0 1 1 8.8 2.8"/><path d="m9 11 3 3L22 4"/></svg>
              </i>
            </span>
            <span class="stat-value">
              <b class="mono">{{ stats.percent }}%</b>
              <em class="mono">{{ stats.closed }} / {{ stats.total }}</em>
            </span>
            <span class="stat-progress"><i :style="{ width: `${stats.percent}%` }" /></span>
          </button>

          <button type="button" class="stat-card" @click="focusState('OPEN')">
            <span class="stat-head">
              <span>待处理</span>
              <i class="stat-icon gray">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
              </i>
            </span>
            <span class="stat-value"><b class="mono">{{ stats.open }}</b><em>个任务</em></span>
            <span class="stat-foot">全部未关闭工单</span>
          </button>

          <button type="button" class="stat-card" @click="focusState('CLOSED')">
            <span class="stat-head">
              <span>已完成</span>
              <i class="stat-icon green">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6 9 17l-5-5"/></svg>
              </i>
            </span>
            <span class="stat-value"><b class="mono">{{ stats.closed }}</b><em>个任务</em></span>
            <span class="stat-foot">近 7 天闭环 {{ weeklyDone }} 个</span>
          </button>

          <button type="button" class="stat-card" @click="focusPriority('P0')">
            <span class="stat-head">
              <span>P0 紧急</span>
              <i class="stat-icon rose">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3c1 3 4 4.5 4 8a4 4 0 0 1-8 0c0-1.4.5-2.4 1-3.2"/><path d="M12 21a7 7 0 0 0 7-7"/></svg>
              </i>
            </span>
            <span class="stat-value"><b class="mono">{{ stats.p0 }}</b><em>个任务</em></span>
            <span class="stat-foot" :class="{ ok: stats.p0 === 0 }">
              {{ stats.p0 === 0 ? '无紧急阻塞' : '需要立即处理' }}
            </span>
          </button>

          <button type="button" class="stat-card" @click="focusState('')">
            <span class="stat-head">
              <span>同步失败</span>
              <i class="stat-icon gray">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M17.5 19a4.5 4.5 0 0 0 .7-8.95A6 6 0 0 0 6.2 9.2 4 4 0 0 0 6 17"/><path d="m9 15 6-6"/></svg>
              </i>
            </span>
            <span class="stat-value"><b class="mono">{{ syncStats.failed }}</b><em>个任务</em></span>
            <span class="stat-foot" :class="{ ok: syncStats.failed === 0 }">
              {{ syncStats.failed === 0 ? '全部同步正常' : '需要重试' }}
            </span>
          </button>

          <div class="risk-card">
            <div class="risk-card-head">
              <span>风险项</span>
              <em class="mono" :class="{ ok: riskTasks.length === 0 }">{{ riskTasks.length }}</em>
            </div>
            <p class="risk-card-body" :class="{ ok: riskTasks.length === 0 }">
              {{ riskTasks.length === 0 ? '所有任务同步正常' : '存在同步失败的任务，请尽快重试' }}
            </p>
            <div class="risk-card-actions">
              <button type="button" class="primary-btn" @click="openCreate">新建任务</button>
            </div>
          </div>
        </div>

        <!-- 第一行：新增趋势 + 四象限 -->
        <div class="board-grid wide">
          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">新增任务趋势</span>
                <span class="bpanel-hint">
                  近 {{ chartRange }} 天新增 {{ chartTotals.created }} 个
                  <span class="hint-sep">·</span>
                  Bug {{ chartTotals.bug }} / Feat {{ chartTotals.feat }}
                </span>
              </div>
              <div class="seg-switch">
                <button
                  type="button" :class="{ active: chartRange === 7 }"
                  @click="chartRange = 7"
                >7 天</button>
                <button
                  type="button" :class="{ active: chartRange === 30 }"
                  @click="chartRange = 30"
                >30 天</button>
              </div>
            </div>
            <div class="chart-legend">
              <span><i class="legend-dot bug" />Bug</span>
              <span><i class="legend-dot feat" />Feat</span>
            </div>
            <VisXYContainer :data="chartBuckets" :height="228" class="chart-box">
              <VisStackedBar
                :data="chartBuckets" :x="bucketX" :y="trendY"
                :color="['#EF4444', '#8B5CF6']" :roundedCorners="4" :barMaxWidth="26"
              />
              <VisAxis
                type="x" :tickValues="chartTicks" :tickFormat="chartTickFormat"
                :gridLine="false" :tickLine="false" :domainLine="false" :tickPadding="10"
              />
              <VisTooltip :triggers="trendTriggers" />
            </VisXYContainer>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">任务四象限</span>
                <span class="bpanel-hint">横轴积压时长 · 纵轴优先级</span>
              </div>
              <span class="bpanel-tag">{{ quadrantPoints.length }} 未关闭</span>
            </div>
            <VisXYContainer
              :data="quadrantPoints" :height="228"
              :xDomain="[0, quadMaxAge]" :yDomain="[0.5, 3.5]"
            >
              <VisScatter
                :data="quadrantPoints" :x="quadX" :y="quadY" :size="quadSize"
                :color="quadColor" strokeColor="#fff" :strokeWidth="2"
              />
              <VisAxis
                type="x" :tickFormat="quadAgeTick"
                :gridLine="false" :tickLine="false" :domainLine="false" :tickPadding="8"
              />
              <VisAxis
                type="y" :tickValues="[1, 2, 3]" :tickFormat="priorityTick"
                :gridLine="false" :tickLine="false" :domainLine="false" :tickPadding="8"
              />
              <VisPlotline
                axis="x" :value="quadSplitX" :lineWidth="1" :lineStyle="[4, 4]" color="#E2E8F0"
              />
              <VisPlotline
                axis="y" :value="1.5" :lineWidth="1" :lineStyle="[4, 4]" color="#E2E8F0"
              />
              <VisTooltip :triggers="quadrantTriggers" />
            </VisXYContainer>
            <div class="chart-legend quad-foot">
              <span><i class="legend-dot bug" />Bug</span>
              <span><i class="legend-dot feat" />Feat</span>
              <span class="quad-hint">右上角需优先清理</span>
            </div>
          </div>
        </div>

        <!-- 第二行：类型构成 / 优先级矩阵 / 完成吞吐 -->
        <div class="board-grid three">
          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">类型构成</span>
                <span class="bpanel-hint">共 {{ stats.total }} 个任务</span>
              </div>
            </div>
            <VisSingleContainer :data="donutData" :height="176" class="chart-box">
              <VisDonut
                :value="donutValue" :color="donutColor" :arcWidth="16"
                :cornerRadius="4" :padAngle="0.02"
                :centralLabel="String(stats.total)" centralSubLabel="任务总数"
              />
              <VisTooltip :triggers="donutTriggers" />
            </VisSingleContainer>
            <div class="type-legend">
              <span><i class="legend-dot bug" />Bug <b class="mono">{{ stats.bug }}</b></span>
              <span><i class="legend-dot feat" />Feat <b class="mono">{{ stats.feat }}</b></span>
            </div>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">未完成优先级矩阵</span>
                <span class="bpanel-hint">优先级 × 类型</span>
              </div>
              <span class="bpanel-tag">{{ stats.open }} 待处理</span>
            </div>
            <div class="matrix-head">
              <span />
              <span>Bug</span>
              <span>Feat</span>
            </div>
            <button
              v-for="cell in priorityMatrix" :key="cell.key" type="button"
              class="matrix-row" @click="focusPriority(cell.key)"
            >
              <span class="pbar-key" :class="cell.key.toLowerCase()">{{ cell.key }}</span>
              <span class="matrix-cell mono">{{ cell.bug || '–' }}</span>
              <span class="matrix-cell mono">{{ cell.feat || '–' }}</span>
            </button>
            <div class="matrix-foot">
              <span class="bpanel-hint">0 值不展示，避免噪音</span>
            </div>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">完成吞吐</span>
                <span class="bpanel-hint">
                  近 {{ chartRange }} 天完成 {{ chartTotals.done }} 个
                  <span class="hint-sep">·</span>
                  平均闭环 {{ avgCycleHours }}h
                </span>
              </div>
            </div>
            <VisXYContainer :data="chartBuckets" :height="176" class="chart-box">
              <VisArea
                :data="chartBuckets" :x="bucketX" :y="doneY"
                :curveType="CurveType.MonotoneX" color="rgba(37, 99, 235, 0.16)"
                :line="true" lineColor="#2563EB" :lineWidth="2"
              />
              <VisAxis
                type="x" :tickValues="chartTicks" :tickFormat="chartTickFormat"
                :gridLine="false" :tickLine="false" :domainLine="false" :tickPadding="10"
              />
              <VisCrosshair :template="throughputTemplate" color="#2563EB" />
              <VisTooltip />
            </VisXYContainer>
          </div>
        </div>

        <!-- 第三行：优先处理 + 风险项 -->
        <div class="board-grid wide">
          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">优先处理</span>
                <span class="bpanel-hint">未关闭任务按优先级排序</span>
              </div>
            </div>
            <div v-if="!focusTasks.length" class="board-empty">暂无未关闭任务</div>
            <button
              v-for="row in focusTasks" :key="row.id" type="button"
              class="focus-row" @click="openFromBoard(row)"
            >
              <span class="prio-line" :class="(row.priority ?? 'P2').toLowerCase()" />
              <span class="prio-pill" :class="(row.priority ?? 'P2').toLowerCase()">
                {{ row.priority ?? 'P2' }}
              </span>
              <span class="version-chip mono" :class="{ empty: !row.version }">
                {{ row.version ? `v${row.version}` : '未关联版本' }}
              </span>
              <span class="focus-title">{{ row.title }}</span>
              <span class="focus-meta">{{ kindLabel(row.kind) }} · {{ formatTime(row.updatedAt) }}</span>
              <span class="focus-arrow">→</span>
            </button>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <div>
                <span class="bpanel-title">风险项</span>
                <span class="bpanel-hint">远端同步失败的任务</span>
              </div>
            </div>
            <div v-if="!riskTasks.length" class="risk-ok">
              <span class="risk-ok-dot" />
              所有任务同步正常
            </div>
            <button
              v-for="row in riskTasks" :key="row.id" type="button"
              class="focus-row" @click="openFromBoard(row)"
            >
              <span class="risk-badge">同步失败</span>
              <span class="focus-title">{{ row.title }}</span>
              <span class="focus-arrow">→</span>
            </button>
            <div class="sync-summary">
              <span><i class="legend-dot synced" />已同步 <b class="mono">{{ syncStats.synced }}</b></span>
              <span><i class="legend-dot local" />仅本地 <b class="mono">{{ syncStats.local }}</b></span>
              <span><i class="legend-dot failed" />失败 <b class="mono">{{ syncStats.failed }}</b></span>
            </div>
          </div>
        </div>
      </section>

      <!-- 列表视图的轻量数字条：不进看板也能看到关键指标 -->
      <div v-if="taskView === 'list'" class="list-stats">
        <span><em>待处理</em><b class="mono">{{ stats.open }}</b></span>
        <span class="p0"><em>P0 紧急</em><b class="mono">{{ stats.p0 }}</b></span>
        <span class="bug"><em>近 7 天新增 Bug</em><b class="mono">{{ weeklyBugs }}</b></span>
        <button type="button" class="list-stats-more" @click="taskView = 'board'">查看看板 →</button>
      </div>

      <div v-if="taskView === 'list'" class="issues-body">
        <!-- 左列：搜索 / 过滤 / 任务列表 -->
        <aside class="issues-side">
          <div class="side-toolbar">
            <el-input
              v-model="keyword" size="default" clearable class="search"
              placeholder="搜索标题 / 描述"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>

            <!-- 筛选条：每项都有标签，避免"一排小按钮猜含义" -->
            <div class="filter-bar">
              <label class="filter-item">
                <span class="filter-label">状态</span>
                <el-select v-model="stateFilter" size="small" class="filter-select">
                  <el-option
                    v-for="opt in stateOptions" :key="opt.value"
                    :label="opt.label" :value="opt.value"
                  />
                </el-select>
              </label>
              <label class="filter-item">
                <span class="filter-label">类型</span>
                <el-select v-model="kindFilter" size="small" class="filter-select">
                  <el-option
                    v-for="opt in kindOptions" :key="opt.value"
                    :label="opt.label" :value="opt.value"
                  />
                </el-select>
              </label>
              <label class="filter-item">
                <span class="filter-label">优先级</span>
                <el-select v-model="priorityFilter" size="small" class="filter-select">
                  <el-option
                    v-for="opt in priorityTabs" :key="opt.value"
                    :label="opt.value || '全部'" :value="opt.value"
                  />
                </el-select>
              </label>
              <label class="filter-item">
                <span class="filter-label">版本</span>
                <el-select
                  v-model="versionFilter" size="small" class="filter-select"
                  :disabled="versionOptionsForFilter.length <= 1"
                  :title="versionOptionsForFilter.length <= 1
                    ? '当前任务都没有关联版本号' : '按关联版本号筛选'"
                >
                  <el-option
                    v-for="opt in versionOptionsForFilter" :key="opt.value"
                    :label="opt.label" :value="opt.value"
                  />
                </el-select>
              </label>
              <label class="filter-item">
                <span class="filter-label">排序</span>
                <el-select v-model="sortBy" size="small" class="filter-select">
                  <el-option label="最近更新" value="updated" />
                  <el-option label="优先级" value="priority" />
                  <el-option label="最新创建" value="created" />
                  <el-option label="按标题" value="title" />
                </el-select>
              </label>
            </div>

            <!-- 生效中的条件：单条可去掉，也可一键清空 -->
            <div v-if="activeFilters.length" class="filter-chips">
              <span class="filter-chips-label">已筛选</span>
              <button
                v-for="f in activeFilters" :key="f.key" type="button"
                class="filter-chip" :title="`清除「${f.label}」`"
                @click="f.clear()"
              >
                {{ f.label }}
                <el-icon class="chip-close"><Close /></el-icon>
              </button>
              <button type="button" class="filter-clear" @click="resetFilters">清空全部</button>
            </div>

            <div class="toolbar-count-row">
              <span class="toolbar-count mono">{{ visibleItems.length }} 个任务</span>
              <el-button
                size="small" :icon="Download" :loading="exporting"
                :disabled="!visibleItems.length"
                :title="visibleItems.length
                  ? `导出当前筛选出的 ${visibleItems.length} 条任务` : '没有可导出的任务'"
                @click="exportExcel"
              >
                导出 Excel
              </el-button>
            </div>
          </div>

          <div class="side-list">
            <div v-if="loading && !items.length" class="list-empty">加载中…</div>
            <div v-else-if="error" class="list-empty error">{{ error }}</div>
            <div v-else-if="!visibleItems.length" class="list-empty">
              {{ items.length
                ? '当前分类下没有任务，切换分类看看。'
                : '还没有任务。新建一个，或在 Git 上创建 Issue 自动同步过来。' }}
            </div>

            <button
              v-for="row in visibleItems" :key="row.id" type="button" class="task-item"
              :class="{ active: selectedId === row.id, closed: row.state === 'CLOSED' }"
              @click="select(row)"
            >
              <span class="item-line">
                <span class="state-dot" :class="row.state.toLowerCase()" />
                <span class="prio-chip" :class="(row.priority ?? 'P2').toLowerCase()">
                  {{ row.priority ?? 'P2' }}
                </span>
                <span v-if="row.code" class="task-code mono">{{ row.code }}</span>
                <span v-if="row.version" class="version-chip mono">v{{ row.version }}</span>
                <span class="kind-chip" :class="row.kind === 'FEAT' ? 'feat' : 'bug'">
                  {{ kindLabel(row.kind) }}
                </span>
                <span class="task-title">{{ row.title }}</span>
              </span>
              <span class="item-meta">
                <span class="task-remote mono" :class="{ local: !isBound(row) }">
                  {{ remoteText(row) }}
                </span>
                <span
                  v-if="isBound(row) || row.syncStatus === 'FAILED'"
                  class="sync-pill" :class="row.syncStatus.toLowerCase()"
                >
                  {{ syncLabel(row) }}
                </span>
                <span class="item-labels">
                  <span v-for="label in labelList(row.labels).slice(0, 2)" :key="label" class="mini-chip">
                    {{ label }}
                  </span>
                </span>
                <span class="task-time mono">{{ formatTime(row.updatedAt) }}</span>
              </span>
            </button>
          </div>
        </aside>

        <!-- 右列：详情 / 编辑 / 空态 -->
        <section class="issues-detail">
          <!-- 新建 / 编辑：占据右列，不遮挡列表 -->
          <div v-if="editorOpen" class="pane-scroll">
            <div class="pane-head">
              <div>
                <h2 class="pane-title">{{ editingId ? '编辑任务' : '新建任务' }}</h2>
                <p class="pane-desc">
                  {{ editingId
                    ? '修改后会同步到已绑定的 Git Issue；Agent 也能读取并操作这条任务。'
                    : '保存后自动同步到项目托管的 Git 仓库；Agent 也能读取并操作这条任务。' }}
                </p>
              </div>
              <button type="button" class="pane-close" @click="closeEditor">取消</button>
            </div>
            <div class="editor-body">
              <div class="editor-field">
                <span class="editor-label">标题</span>
                <el-input
                  v-model="form.title" maxlength="200" show-word-limit
                  placeholder="一句话描述问题"
                />
              </div>
              <div class="editor-field">
                <div class="editor-label-row">
                  <span class="editor-label">描述</span>
                  <button
                    type="button" class="editor-upload" :disabled="uploading" @click="pickImage"
                  >
                    <el-icon><Picture /></el-icon>{{ uploading ? '上传中…' : '插入图片' }}
                  </button>
                </div>
                <div
                  class="desc-wrap"
                  @paste="onPasteImage($event, 'body')"
                  @dragover.prevent
                  @drop="onDropImage($event, 'body')"
                >
                  <el-input
                    v-model="form.body" type="textarea" :rows="6" resize="none"
                    placeholder="补充背景、复现步骤或期望结果（支持 Markdown，可直接粘贴 / 拖入截图）"
                  />
                  <p class="desc-hint">可直接粘贴截图或拖拽图片到这里，图片会插入到描述末尾。</p>
                </div>
              </div>
              <div class="editor-row">
                <div class="editor-field kind-field">
                  <span class="editor-label">类型</span>
                  <div class="kind-picker">
                    <button
                      type="button" class="kind-option bug"
                      :class="{ active: form.kind === 'BUG' }" @click="form.kind = 'BUG'"
                    >
                      Bug
                    </button>
                    <button
                      type="button" class="kind-option feat"
                      :class="{ active: form.kind === 'FEAT' }" @click="form.kind = 'FEAT'"
                    >
                      Feat
                    </button>
                  </div>
                </div>
                <div class="editor-field priority-field">
                  <span class="editor-label">优先级</span>
                  <div class="prio-picker">
                    <button
                      v-for="p in PRIORITY_VALUES" :key="p" type="button"
                      class="prio-option" :class="[p.toLowerCase(), { active: form.priority === p }]"
                      @click="form.priority = p"
                    >
                      {{ p }}
                    </button>
                  </div>
                </div>
                <div class="editor-field grow">
                  <span class="editor-label">标签</span>
                  <el-input v-model="form.labels" placeholder="bug, 待排查（逗号分隔）" />
                </div>
              </div>
              <div class="editor-field">
                <div class="editor-label-row">
                  <span class="editor-label">关联版本号</span>
                  <span class="editor-hint">
                    {{ versionBranch ? `当前分支 ${versionBranch}` : '仓库不可用，只能手输' }}
                  </span>
                  <button
                    v-if="versionSuggestion && form.version !== versionSuggestion"
                    type="button" class="editor-upload"
                    @click="form.version = versionSuggestion"
                  >
                    使用默认 {{ versionSuggestion }}
                  </button>
                </div>
                <el-select
                  v-model="form.version" filterable allow-create default-first-option clearable
                  placeholder="留空 = 当前分支版本；也可选下个版本的标签"
                >
                  <el-option v-for="tag in versionOptions" :key="tag" :label="tag" :value="tag" />
                </el-select>
                <p class="desc-hint">
                  默认取当前分支版本线的下一个补丁版本；计划下个版本再做的 Feat 可改成目标版本。
                </p>
              </div>
              <div class="editor-target">
                <span class="target-icon"><el-icon><Link /></el-icon></span>
                <span v-if="editorTarget.repo" class="target-text">
                  同步到 {{ providerLabel(editorTarget.provider) }}
                  <span class="mono target-repo">{{ editorTarget.repo }}</span>
                </span>
                <span v-else class="target-text muted">
                  项目未关联 Git 仓库，任务仅保存在平台
                </span>
              </div>
            </div>
            <div class="editor-foot">
              <el-button size="small" @click="closeEditor">取消</el-button>
              <el-button size="small" type="primary" :loading="saving" @click="submit">
                {{ editingId ? '保存' : '创建任务' }}
              </el-button>
            </div>
          </div>

          <template v-else-if="selectedRow">
            <header class="detail-head">
              <div class="detail-head-main">
                <div class="detail-kicker">
                  <span class="prio-pill" :class="(selectedRow.priority ?? 'P2').toLowerCase()">
                    {{ selectedRow.priority ?? 'P2' }}
                  </span>
                  <span class="kind-chip" :class="selectedRow.kind === 'FEAT' ? 'feat' : 'bug'">
                    {{ kindLabel(selectedRow.kind) }}
                  </span>
                  <span class="version-chip" :class="{ empty: !selectedRow.version }">
                    {{ selectedRow.version ? `v${selectedRow.version}` : '未关联版本' }}
                  </span>
                  <span class="state-pill" :class="selectedRow.state.toLowerCase()">
                    {{ selectedRow.state === 'OPEN' ? '待处理' : '已完成' }}
                  </span>
                  <span
                    v-if="isBound(selectedRow) || selectedRow.syncStatus === 'FAILED'"
                    class="sync-pill" :class="selectedRow.syncStatus.toLowerCase()"
                  >
                    {{ syncLabel(selectedRow) }}
                  </span>
                </div>
                <h2 class="detail-title">{{ selectedRow.title }}</h2>
                <div class="detail-meta">
                  <a
                    v-if="isBound(selectedRow)" class="block-link mono"
                    :href="selectedRow.url" target="_blank" rel="noreferrer"
                  >
                    <el-icon><Link /></el-icon> {{ selectedRow.repo }}#{{ selectedRow.number }}
                  </a>
                  <span v-else class="meta-muted mono">仅本地</span>
                  <span class="meta-sep">·</span>
                  <span class="mono">{{ selectedRow.id }}</span>
                  <span class="meta-sep">·</span>
                  <span class="mono">更新 {{ formatTime(selectedRow.updatedAt) }}</span>
                </div>
              </div>
              <div class="detail-actions">
                <el-button size="small" @click="openEdit(selectedRow)">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'state:' + selectedRow.id"
                  @click="toggleState(selectedRow)"
                >
                  <el-icon><component :is="selectedRow.state === 'OPEN' ? Close : RefreshRight" /></el-icon>
                  {{ selectedRow.state === 'OPEN' ? '关闭' : '重开' }}
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'sync:' + selectedRow.id"
                  :disabled="!isBound(selectedRow)" @click="resync(selectedRow)"
                >
                  <el-icon><Refresh /></el-icon> 同步
                </el-button>
                <el-button
                  size="small" type="danger" plain
                  :loading="actionBusy === 'delete:' + selectedRow.id" @click="removeIssue(selectedRow)"
                >
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </div>
            </header>

            <div class="pane-scroll">
              <div v-if="selectedRow.syncError" class="sync-error">{{ selectedRow.syncError }}</div>

              <section class="detail-block">
                <div class="block-head">
                  <span class="block-title">描述</span>
                  <span class="block-sub mono">{{ selectedRow.id }}</span>
                </div>
                <div v-if="!selectedRow.body" class="block-empty">（无描述）</div>
                <div v-else class="markdown-body">
                  <MarkdownView :content="selectedRow.body" />
                </div>
              </section>

              <section class="comment-block">
                <div class="block-head">
                  <span class="block-title">评论</span>
                  <span class="block-sub">
                    {{ comments.length }} 条 · 平台与 Git 双向同步
                  </span>
                </div>

                <div v-if="commentsLoading" class="block-empty">加载评论…</div>
                <div v-else-if="!comments.length" class="block-empty">还没有评论</div>
                <div v-else class="comment-list">
                  <div v-for="c in comments" :key="c.id" class="comment-item">
                    <div class="comment-head">
                      <span class="comment-author">
                        <el-icon><ChatDotRound /></el-icon>
                        {{ c.author }}
                      </span>
                      <span class="comment-source" :class="c.source.toLowerCase()">
                        {{ c.source === 'GIT' ? 'Git' : c.source === 'AGENT' ? 'Agent' : '平台' }}
                      </span>
                      <span class="comment-time mono">{{ formatTime(c.createdAt) }}</span>
                      <button
                        v-if="c.source === 'PLATFORM'" type="button" class="comment-del"
                        @click="removeComment(c)"
                      >
                        删除
                      </button>
                    </div>
                    <div class="comment-body">{{ c.body }}</div>
                  </div>
                </div>

                <div
                  class="comment-compose"
                  @paste="onPasteImage($event, 'comment')"
                  @dragover.prevent
                  @drop="onDropImage($event, 'comment')"
                >
                  <el-input
                    v-model="commentText" type="textarea" :rows="2" resize="none"
                    placeholder="写评论；已绑定 Git 的任务会同步为远端评论（可粘贴图片）"
                  />
                  <div class="compose-foot">
                    <span class="compose-hint">Agent 也可以直接调用工具评论 / 关闭任务</span>
                    <button
                      type="button" class="editor-upload" :disabled="uploading" @click="pickImage"
                    >
                      <el-icon><Picture /></el-icon>{{ uploading ? '上传中…' : '图片' }}
                    </button>
                    <el-button
                      size="small" type="primary" :loading="commenting"
                      :disabled="!commentText.trim()" @click="submitComment"
                    >
                      发表评论
                    </el-button>
                  </div>
                </div>
              </section>
            </div>
          </template>

          <div v-else class="detail-empty">
            <span class="empty-icon"><el-icon><Tickets /></el-icon></span>
            <h3 class="empty-title">选择一个任务查看详情</h3>
            <p class="empty-desc">
              左侧任务与 Git 仓库双向同步；也可以新建任务，或让 Agent 在对话里直接创建。
            </p>
            <el-button size="small" type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon> 新建任务
            </el-button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issues-view {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  padding: 18px 22px 22px;
}
.issues-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  min-height: 0;
}

/* ---- 页头 ---- */
.issues-head {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 16px;
}
.issues-head-main {
  flex: 1;
  min-width: 0;
}
.issues-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.issues-title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: var(--ink);
}
/* 列表 / 看板切换 */
.view-switch {
  display: flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.view-switch-btn {
  padding: 3px 12px;
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition: background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.view-switch-btn:hover {
  color: var(--ink);
}
.view-switch-btn.active {
  color: var(--accent-ink, #4a6cf7);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.issues-sub {
  font-size: 11px;
  color: var(--ink-faint);
}
.issues-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.issues-head-side {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.repo-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 4px 10px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.repo-chip .mono {
  color: var(--ink);
}
.repo-dot {
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
}

/* ---- 量化看板视图 ---- */
.board-view {
  --vis-font-family: inherit;
  --vis-axis-tick-label-color: var(--ink-faint);
  --vis-axis-tick-label-font-size: 10px;
  --vis-axis-tick-label-weight: 500;
  --vis-axis-grid-color: transparent;
  --vis-axis-domain-color: transparent;
  --vis-axis-tick-color: transparent;
  --vis-tooltip-background-color: rgb(15 23 42 / 0.96);
  --vis-tooltip-text-color: #f8fafc;
  --vis-tooltip-border-color: transparent;
  --vis-tooltip-border-radius: 8px;
  --vis-tooltip-padding: 8px 12px;
  --vis-tooltip-box-shadow: 0 8px 24px rgb(15 23 42 / 18%);
  --vis-donut-background-color: #f1f5f9;
  --vis-donut-central-label-text-color: var(--ink);
  --vis-donut-central-label-font-size: 26px;
  --vis-donut-central-label-font-weight: 700;
  --vis-donut-central-sub-label-text-color: var(--ink-faint);

  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding-bottom: 4px;
  overflow-y: auto;
}

/* 关键指标条 */
.kpi-strip {
  display: grid;
  flex: none;
  grid-template-columns: repeat(5, minmax(0, 1fr)) minmax(190px, 1.15fr);
  gap: 12px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 14px 16px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg, 12px);
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
.stat-card:hover {
  transform: translateY(-2px);
  border-color: var(--line);
  box-shadow: 0 10px 24px rgb(15 23 42 / 8%);
}
.stat-head {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-muted);
}
.stat-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
}
.stat-icon svg { width: 14px; height: 14px; }
.stat-icon.blue { color: #2563eb; background: #eff6ff; }
.stat-icon.gray { color: var(--ink-muted); background: var(--surface-sunken); }
.stat-icon.green { color: var(--ok); background: #ecfdf5; }
.stat-icon.rose { color: #f43f5e; background: #fff1f2; }
.stat-value { display: flex; gap: 6px; align-items: baseline; margin-top: 10px; }
.stat-value b {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--ink);
}
.stat-value em { font-size: 11px; font-style: normal; color: var(--ink-faint); }
.stat-foot { margin-top: 10px; font-size: 10px; color: var(--ink-faint); }
.stat-foot.ok { color: var(--ok); }
.stat-progress {
  height: 5px;
  margin-top: 12px;
  overflow: hidden;
  background: var(--surface-sunken);
  border-radius: 999px;
}
.stat-progress i {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 999px;
  transition: width .5s ease;
}

.risk-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg, 12px);
}
.risk-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-muted);
}
.risk-card-head em { font-style: normal; font-size: 11px; font-weight: 600; color: var(--danger); }
.risk-card-head em.ok { color: var(--ok); }
.risk-card-body { margin: 0; font-size: 10.5px; color: var(--ink-faint); }
.risk-card-body.ok { color: var(--ok); }
.risk-card-actions { display: flex; gap: 6px; margin-top: auto; padding-top: 4px; }
.ghost-btn,
.primary-btn {
  flex: 1;
  height: 26px;
  font-family: inherit;
  font-size: 10.5px;
  font-weight: 600;
  cursor: pointer;
  border-radius: 7px;
}
.ghost-btn {
  color: var(--ink-2, #334155);
  background: var(--surface-raised);
  border: 1px solid var(--line);
}
.ghost-btn:hover { background: var(--surface-sunken); }
.primary-btn { color: #fff; background: var(--accent); border: 1px solid var(--accent); }
.primary-btn:hover { filter: brightness(0.95); }

.board-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.board-grid.wide { grid-template-columns: 1.4fr 1fr; }
.bpanel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg, 12px);
  transition: border-color .18s ease, box-shadow .18s ease;
}
.bpanel:hover { border-color: var(--line); box-shadow: 0 6px 18px rgb(15 23 42 / 5%); }
.bpanel-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
}
.bpanel-title { display: block; font-size: 12px; font-weight: 650; color: var(--ink); }
.bpanel-hint { display: block; margin-top: 2px; font-size: 9.5px; color: var(--ink-faint); }
.hint-sep { margin: 0 4px; color: var(--line); }
.bpanel-tag {
  flex: none;
  padding: 1px 7px;
  font-size: 9.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 999px;
  white-space: nowrap;
}

.seg-switch {
  display: inline-flex;
  flex: none;
  gap: 2px;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: 7px;
}
.seg-switch button {
  padding: 2px 9px;
  font-family: inherit;
  font-size: 10px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 5px;
  transition: .15s ease;
}
.seg-switch button.active {
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: 0 1px 2px rgb(15 23 42 / 8%);
}

.chart-box { width: 100%; margin-top: 4px; }
.chart-legend { display: flex; gap: 14px; align-items: center; font-size: 10px; color: var(--ink-muted); }
.legend-dot { display: inline-block; width: 7px; height: 7px; margin-right: 5px; border-radius: 50%; }
.legend-dot.bug { background: #ef4444; }
.legend-dot.feat { background: #8b5cf6; }
.legend-dot.synced { background: var(--ok); }
.legend-dot.local { background: var(--line); }
.legend-dot.failed { background: var(--danger); }
.quad-foot { margin-top: auto; }
.quad-hint { margin-left: auto; font-size: 9.5px; color: var(--ink-faint); }
.type-legend { display: flex; justify-content: space-between; font-size: 10.5px; color: var(--ink-muted); }
.type-legend b { color: var(--ink); }

/* 优先级矩阵 */
.matrix-head,
.matrix-row {
  display: grid;
  grid-template-columns: 34px 1fr 1fr;
  gap: 6px;
  align-items: center;
}
.matrix-head { font-size: 9px; color: var(--ink-faint); text-align: center; }
.matrix-row {
  padding: 2px 0;
  font-family: inherit;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.matrix-row:hover { background: var(--surface-sunken); }
.pbar-key {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 20px;
  font-size: 10px;
  font-weight: 700;
  border-radius: 6px;
}
.pbar-key.p0 { color: #c23a3a; background: rgb(229 77 77 / 14%); }
.pbar-key.p1 { color: #96660f; background: rgb(184 130 27 / 16%); }
.pbar-key.p2 { color: var(--accent-ink, #4a6cf7); background: rgb(77 130 247 / 13%); }
.matrix-cell {
  display: grid;
  place-items: center;
  height: 34px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-sunken);
  border-radius: 7px;
}
.matrix-row:hover .matrix-cell { background: var(--surface-raised); }
.matrix-foot { padding-top: 6px; margin-top: auto; }

/* 优先处理 / 风险项 */
.focus-row {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 8px 6px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 7px;
  transition: background-color .15s ease;
}
.focus-row:hover { background: var(--surface-sunken); }
.prio-line { width: 3px; height: 22px; flex: none; border-radius: 999px; }
.prio-line.p0 { background: #f43f5e; }
.prio-line.p1 { background: #f59e0b; }
.prio-line.p2 { background: #60a5fa; }
.prio-pill { flex: none; padding: 1px 5px; font-size: 9.5px; font-weight: 700; border-radius: 4px; }
.prio-pill.p0 { color: #c23a3a; background: rgb(229 77 77 / 14%); }
.prio-pill.p1 { color: #96660f; background: rgb(184 130 27 / 16%); }
.prio-pill.p2 { color: var(--accent-ink, #4a6cf7); background: rgb(77 130 247 / 13%); }
.focus-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.focus-meta { flex: none; font-size: 9.5px; color: var(--ink-faint); }
.focus-arrow {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
  transition: transform .15s ease, color .15s ease;
}
.focus-row:hover .focus-arrow { color: var(--accent); transform: translateX(2px); }
.risk-ok {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 6px;
  font-size: 11px;
  color: var(--ok);
}
.risk-ok-dot {
  width: 7px;
  height: 7px;
  background: var(--ok);
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgb(46 163 107 / 15%);
}
.risk-badge {
  flex: none;
  padding: 1px 5px;
  font-size: 9px;
  font-weight: 600;
  color: var(--danger);
  background: rgb(229 77 77 / 12%);
  border-radius: 4px;
}
.board-empty { padding: 16px 6px; font-size: 11px; color: var(--ink-faint); text-align: center; }
.sync-summary {
  display: flex;
  gap: 12px;
  padding-top: 8px;
  margin-top: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
  border-top: 1px dashed var(--line-soft);
}
.sync-summary b { color: var(--ink); }

@media (max-width: 1100px) {
  .kpi-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .board-grid,
  .board-grid.wide { grid-template-columns: 1fr; }
}

/* ---- 列表视图数字条 ---- */
.list-stats {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.list-stats span {
  display: inline-flex;
  gap: 6px;
  align-items: baseline;
  padding: 2px 8px;
  font-size: 10px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 6px;
}
.list-stats em {
  font-style: normal;
  color: var(--ink-faint);
}
.list-stats b {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
}
.list-stats .p0 b { color: var(--danger); }
.list-stats .bug b { color: var(--warn); }
.list-stats-more {
  margin-left: auto;
  padding: 2px 4px;
  font-family: inherit;
  font-size: 10px;
  color: var(--accent-ink, #4a6cf7);
  cursor: pointer;
  background: none;
  border: 0;
}
.list-stats-more:hover {
  text-decoration: underline;
}

/* ---- 左右分栏 ---- */
.issues-body {
  display: flex;
  flex: 1;
  gap: 14px;
  min-height: 0;
}
.issues-side {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 340px;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.side-toolbar {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 9px;
  padding: 12px;
  background: var(--sidebar-background-color);
  border-bottom: 1px solid var(--line-soft);
}
.search {
  width: 100%;
}
.toolbar-count-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}
.toolbar-hint {
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 筛选条：每项带标签，两列自适应；比"一排无标签小按钮"更容易看懂当前条件 */
.filter-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(126px, 1fr));
  gap: 6px 8px;
}
.filter-item {
  display: flex;
  gap: 5px;
  align-items: center;
  min-width: 0;
}
.filter-label {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
.filter-select {
  flex: 1 1 auto;
  min-width: 0;
}
.filter-bar :deep(.el-select__wrapper) {
  padding: 2px 8px;
  font-size: 11px;
  min-height: 26px;
}
/* 生效中的条件：单条可撤销，也可整排清空 */
.filter-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
}
.filter-chips-label {
  font-size: 10px;
  color: var(--ink-faint);
}
.filter-chip {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  padding: 2px 7px;
  font-family: inherit;
  font-size: 10px;
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
}
.filter-chip:hover {
  color: var(--ink);
  border-color: var(--line-strong);
}
.chip-close {
  font-size: 10px;
  opacity: 0.6;
}
.filter-chip:hover .chip-close {
  opacity: 1;
}
.filter-clear {
  padding: 2px 4px;
  font-family: inherit;
  font-size: 10px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline;
}
.filter-clear:hover {
  color: var(--ink);
}
.toolbar-count {
  font-size: 10px;
  color: var(--ink-faint);
}
.side-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
  padding: 6px;
  overflow-y: auto;
}
.list-empty {
  padding: 28px 16px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
  text-align: center;
}
.list-empty.error {
  color: var(--danger, #c45656);
}

/* 任务行：两行式列表项，选中后整行浅蓝底 */
.task-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  padding: 10px 11px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 9px;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.task-item:hover {
  background: var(--surface-hover);
}
.task-item.active {
  background: var(--accent-soft);
}
.task-item.active .task-title {
  font-weight: 600;
}
.item-line {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}
.state-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.state-dot.open {
  background: var(--warn, #d19a00);
}
.state-dot.closed {
  background: var(--ok);
}
.task-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-item.closed .task-title {
  color: var(--ink-muted);
}
.item-meta {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  padding-left: 13px;
  font-size: 10px;
  color: var(--ink-faint);
}
.item-labels {
  display: flex;
  gap: 4px;
  overflow: hidden;
}
.kind-chip {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 600;
  line-height: 16px;
  letter-spacing: 0.02em;
  border-radius: 4px;
}
/* 任务编码：FEAT-1 / BUG-3，便于口头与提交信息引用 */
.task-code {
  flex: none;
  font-size: 10px;
  color: var(--ink-soft);
}
/* 关联版本徽标：列表 / 详情 / 看板共用；空值弱化提示未关联 */
.version-chip {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 600;
  line-height: 16px;
  color: #2563eb;
  white-space: nowrap;
  background: rgba(37, 99, 235, 0.08);
  border: 1px solid rgba(37, 99, 235, 0.16);
  border-radius: 4px;
}
.version-chip.empty {
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-color: transparent;
}
/* 优先级徽标：P0 红 / P1 琥珀 / P2 蓝，列表与详情共用 */
.prio-chip,
.prio-pill {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 700;
  line-height: 16px;
  letter-spacing: 0.03em;
  border-radius: 4px;
}
.prio-pill {
  line-height: 17px;
  border-radius: var(--radius-pill);
}
.prio-chip.p0,
.prio-pill.p0 {
  color: #c23a3a;
  background: rgb(229 77 77 / 14%);
}
.prio-chip.p1,
.prio-pill.p1 {
  color: #96660f;
  background: rgb(184 130 27 / 16%);
}
.prio-chip.p2,
.prio-pill.p2 {
  color: var(--accent-ink, #4a6cf7);
  background: rgb(77 130 247 / 13%);
}
.kind-chip.bug {
  color: #b54a4a;
  background: rgb(181 74 74 / 10%);
}
.kind-chip.feat {
  color: var(--accent-ink, #4a6cf7);
  background: rgb(74 108 247 / 12%);
}
.mini-chip {
  overflow: hidden;
  padding: 0 6px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.task-remote {
  flex: none;
  font-size: 10px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.task-remote.local {
  color: var(--ink-faint);
}
.sync-pill {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  line-height: 16px;
  white-space: nowrap;
  border-radius: 4px;
}
.sync-pill.synced {
  color: var(--ok-ink, #2f7d4f);
  background: var(--ok-soft, #e8f5ec);
}
.sync-pill.failed {
  color: var(--danger, #c45656);
  background: var(--danger-soft, #fdecec);
}
.sync-pill.local,
.sync-pill.pending {
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
.task-time {
  flex: none;
  margin-left: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
  white-space: nowrap;
}

/* ---- 右列详情 ---- */
.issues-detail {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.detail-head {
  display: flex;
  flex: none;
  gap: 16px;
  align-items: flex-start;
  padding: 16px 20px 14px;
  border-bottom: 1px solid var(--line-soft);
}
.detail-head-main {
  flex: 1;
  min-width: 0;
}
.detail-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.state-pill {
  padding: 0 7px;
  font-size: 9.5px;
  line-height: 17px;
  border-radius: var(--radius-pill);
}
.state-pill.open {
  color: var(--warn-ink, #8a5a12);
  background: var(--warn-soft);
}
.state-pill.closed {
  color: var(--ok-ink, #1b7a4f);
  background: var(--ok-soft);
}
.detail-title {
  margin: 7px 0 0;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.45;
  letter-spacing: -0.01em;
  color: var(--ink);
  overflow-wrap: anywhere;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 7px;
  font-size: 10px;
  color: var(--ink-faint);
}
.meta-sep {
  color: var(--ink-faint);
}
.meta-muted {
  color: var(--ink-faint);
}
.detail-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}
.pane-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 16px 20px 24px;
  overflow-y: auto;
}
.sync-error {
  font-size: 11px;
  line-height: 1.6;
  color: var(--danger, #c45656);
  overflow-wrap: anywhere;
}
.detail-block,
.comment-block {
  display: flex;
  flex: none;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.block-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: baseline;
  padding: 9px 13px;
  background: var(--code-bg);
  border-bottom: 1px solid var(--line-soft);
}
.block-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.block-sub {
  overflow: hidden;
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 10.5px;
  color: var(--accent-ink);
  text-decoration: none;
}
.block-link:hover {
  text-decoration: underline;
}
.block-empty {
  padding: 14px 13px;
  font-size: 11px;
  color: var(--ink-faint);
}
.markdown-body {
  padding: 12px 14px;
}
.markdown-body :deep(.markdown-view) {
  font-size: 12.5px;
  line-height: 1.75;
}

/* ---- 空态 ---- */
.detail-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  text-align: center;
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-bottom: 4px;
  font-size: 20px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-lg);
}
.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-soft);
}
.empty-desc {
  max-width: 26rem;
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-muted);
}

/* ---- 编辑面板 ---- */
.pane-head {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line-soft);
}
.pane-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
}
.pane-desc {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.pane-close {
  flex: none;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.pane-close:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.editor-head {
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.editor-title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ink);
}
.editor-desc {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.editor-body {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 12px;
}
.editor-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.kind-field {
  flex: none;
}
.kind-picker {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.kind-option {
  padding: 4px 12px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.kind-option.bug.active {
  font-weight: 600;
  color: #b54a4a;
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.kind-option.feat.active {
  font-weight: 600;
  color: var(--accent-ink, #4a6cf7);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.priority-field {
  flex: none;
}
.prio-picker {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.prio-option {
  padding: 4px 10px;
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.prio-option.active {
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.prio-option.p0.active { color: var(--danger); }
.prio-option.p1.active { color: var(--warn); }
.prio-option.p2.active { color: var(--accent); }
.editor-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
}
.editor-label-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}
/* 版本号输入框旁的来源提示：说明默认值是怎么来的（当前分支版本线 / 最新标签）。 */
.editor-hint {
  font-size: 10px;
  color: var(--ink-muted);
  margin-right: auto;
}
.editor-upload {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.editor-upload:hover:not(:disabled) {
  color: var(--accent-ink);
  border-color: var(--accent);
}
.editor-upload:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.desc-wrap {
  position: relative;
}
.desc-hint {
  margin: 4px 0 0;
  font-size: 10px;
  color: var(--ink-faint);
}
.hidden-file {
  display: none;
}
.editor-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}
.grow {
  flex: 1;
  min-width: 0;
}
.editor-target {
  display: flex;
  flex: none;
  gap: 7px;
  align-items: center;
  padding: 8px 11px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 8px;
}
.target-icon {
  display: inline-flex;
  flex: none;
  color: var(--ink-faint);
}
.target-text {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  min-width: 0;
}
.target-text.muted {
  color: var(--ink-faint);
}
.target-repo {
  padding: 1px 5px;
  font-size: 10px;
  color: var(--ink);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 4px;
}
.editor-foot {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}
.comment-list {
  display: flex;
  flex-direction: column;
}
.comment-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 13px;
}
.comment-item + .comment-item {
  border-top: 1px solid var(--line-soft);
}
.comment-head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.comment-author {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
}
.comment-source {
  padding: 0 5px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 4px;
}
.comment-source.git {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.comment-time {
  flex: 1;
  font-size: 10px;
  color: var(--ink-faint);
}
.comment-del {
  padding: 1px 6px;
  font-size: 10px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.comment-del:hover {
  color: var(--danger, #c45656);
  background: var(--surface-hover);
}
.comment-body {
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.comment-compose {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 11px 13px;
  border-top: 1px solid var(--line-soft);
}
.compose-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}
.compose-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* ---- 响应式 ---- */
@media (max-width: 1080px) {
  .issues-side {
    width: 300px;
  }
}
@media (max-width: 900px) {
  .issues-view {
    padding: 14px;
    overflow-y: auto;
  }
  .issues-shell {
    flex: none;
    min-height: auto;
  }
  .issues-head {
    flex-direction: column;
    gap: 10px;
  }
  .issues-head-side {
    justify-content: flex-start;
  }
  .issues-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .issues-body {
    flex: none;
    flex-direction: column;
    min-height: auto;
  }
  .issues-side {
    width: auto;
    max-height: 360px;
  }
  .side-toolbar .state-tabs {
    max-width: 380px;
  }
  .issues-detail {
    flex: none;
    min-height: 520px;
    max-height: 80vh;
  }
  .detail-head {
    flex-direction: column;
    gap: 10px;
  }
  .detail-actions {
    justify-content: flex-start;
  }
  .editor-row {
    flex-direction: column;
    align-items: stretch;
  }
  .pane-scroll {
    padding: 14px 16px 20px;
  }
}
@media (max-width: 600px) {
  .item-labels {
    display: none;
  }
  .detail-meta {
    font-size: 9.5px;
  }
}
</style>
