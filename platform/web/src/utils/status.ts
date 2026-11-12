/** 运行/阶段状态的中文化与色调映射（UI 层唯一出处，避免各组件各写一份）。 */

export type StatusTone = 'ok' | 'bad' | 'busy' | 'idle'

const LABELS: Record<string, string> = {
  QUEUED: '排队中',
  PENDING: '等待中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  CANCELLED: '已取消',
  SKIPPED: '已跳过',
}

/** 中文状态名；未知状态原样返回（避免吞掉后端新枚举）。 */
export function statusLabel(status: string): string {
  return LABELS[status] ?? status
}

export function statusTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'ok'
  if (status === 'FAILED' || status === 'TIMEOUT' || status === 'CANCELLED') return 'bad'
  if (status === 'RUNNING' || status === 'QUEUED' || status === 'PENDING') return 'busy'
  return 'idle'
}

/** elapsed 格式化：1.2s / 1m 05s。 */
export function formatDuration(ms: number): string {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${ms} ms`
  const totalSeconds = Math.floor(ms / 1000)
  if (totalSeconds < 60) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

const pad2 = (n: number) => String(n).padStart(2, '0')

/** 时间戳格式化：MM-DD HH:mm:ss。 */
export function formatTime(ms: number): string {
  if (!ms) return '—'
  const d = new Date(ms)
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${formatClockSeconds(ms)}`
}

/** 只到分钟：HH:mm。 */
export function formatClock(ms: number): string {
  const d = new Date(ms)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** 到秒：HH:mm:ss。 */
export function formatClockSeconds(ms: number): string {
  const d = new Date(ms)
  return `${formatClock(ms)}:${pad2(d.getSeconds())}`
}

/** 日期：YYYY-MM-DD。 */
export function formatDate(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/** 日期时间：YYYY-MM-DD HH:mm。 */
export function formatDateTime(ms: number): string {
  return `${formatDate(ms)} ${formatClock(ms)}`
}

/** 精确到毫秒：YYYY-MM-DD HH:mm:ss.SSS。 */
export function formatDateTimeMillis(ms: number): string {
  return `${formatDate(ms)} ${formatClockSeconds(ms)}.${String(new Date(ms).getMilliseconds()).padStart(3, '0')}`
}
