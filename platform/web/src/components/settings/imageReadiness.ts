/**
 * 镜像就绪视图的纯展示逻辑（能脱离 Vue 单测，所以不写在 .vue 里）。
 *
 * <p>「退避中」是这里最容易被写错的地方：`retryInSeconds` 为 0 只说明"现在可以再试"，
 * 不代表已就绪；就绪与否只看 `status`。两者混在一起会让人误以为失败已经好了。
 */
import type { SandboxImage } from '../../api/settings'

/** 状态标签：就绪 / 失败（退避中）。 */
export function statusLabel(row: Pick<SandboxImage, 'status'>): string {
  return row.status === 'READY' ? '已就绪' : '失败'
}

/** el-tag 的类型：就绪=成功，失败=危险。 */
export function statusTagType(row: Pick<SandboxImage, 'status'>): 'success' | 'danger' {
  return row.status === 'READY' ? 'success' : 'danger'
}

/**
 * 下次重试的人话描述。
 *
 * <p>只对 FAILED 有意义；超过 1 分钟按分钟说，避免出现「还要等 117 秒」这种读不出量级的值。
 */
export function retryText(row: Pick<SandboxImage, 'status' | 'retryInSeconds'>): string {
  if (row.status !== 'FAILED' || row.retryInSeconds <= 0) {
    return '—'
  }
  if (row.retryInSeconds < 60) {
    return `${row.retryInSeconds} 秒后可重试`
  }
  const minutes = Math.ceil(row.retryInSeconds / 60)
  return `约 ${minutes} 分钟后重试`
}

/** 来源列：local=本机已有，其余是拉取时用的加速源/缓存源。 */
export function sourceText(row: Pick<SandboxImage, 'source'>): string {
  if (!row.source) return '—'
  return row.source === 'local' ? '本机已有' : row.source
}

/** 失败的镜像数量（面板顶部提示用）。 */
export function failedCount(rows: SandboxImage[]): number {
  return rows.filter((row) => row.status === 'FAILED').length
}
