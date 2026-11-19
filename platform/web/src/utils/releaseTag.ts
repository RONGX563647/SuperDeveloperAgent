/**
 * 发布标签状态的展示口径。
 *
 * <p>背景（BUG-10「打 tag 未生效」）：后端把 LOCAL（标签只在本机）
 * 与原因码下发给了界面，但界面原来完全不展示，用户只能到远端自己发现没有标签。
 * 把映射抽到这里，视图与回归脚本共用同一份，避免文案与后端枚举脱节。
 */

/** 状态 → 文案；返回空串表示不需要展示（快照构建没有标签）。 */
export function tagStateText(state: string | undefined): string {
  if (!state) return ''
  switch (state) {
    case 'PUSHED':
      return '标签已推送'
    case 'LOCAL':
      return '标签仅本地'
    case 'FAILED':
      return '标签创建失败'
    case 'REJECTED':
      return '标签被拒绝'
    case 'SNAPSHOT':
      return ''
    default:
      return state
  }
}

/**
 * 是否需要警示。
 *
 * <p>判据是「远端是否真的可见」：只有 PUSHED 才算生效。
 * 快照构建本来就不打标签，不该报警。
 */
export function tagNeedsAttention(state: string | undefined): boolean {
  if (!state || state === 'SNAPSHOT') return false
  return state !== 'PUSHED'
}
