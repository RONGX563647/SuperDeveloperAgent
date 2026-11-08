/**
 * 项目 ID 自动推导（接入向导用）。
 *
 * <p>需求来自实际操作反馈：**换一个目录/仓库后，项目 ID 必须跟着变**，
 * 但用户手改过的 ID 不能被自动覆盖（否则等于跟用户抢输入框）。
 *
 * <p>抽成纯函数的原因：这条规则有两个分支（跟随 / 保留），
 * 之前写成"ID 非空就直接 return"，导致第二次选目录时 ID 还是上一个项目名，
 * 提交时撞上已登记的 projectId。纯函数可以脱离浏览器直接跑断言。
 */

/** 目录名 / 仓库名 → 合法 projectId（大写、只留字母数字、最多 16 位）。 */
export function idFromName(name: string): string {
  return String(name ?? '').replace(/[^A-Za-z0-9]/g, '').toUpperCase().slice(0, 16)
}

/**
 * 是否该用自动建议覆盖当前值。
 *
 * @param current       输入框当前值
 * @param lastSuggested 上次自动填进去的值（空 = 还没自动填过）
 */
export function shouldApplySuggestion(current: string, lastSuggested: string): boolean {
  if (!current) return true          // 空 → 直接填
  return current === lastSuggested   // 仍是自动值 → 跟着选择更新；已手改 → 保留
}

/** 自动 ID 的状态：当前值 + 上次自动值（用来区分人工输入）。 */
export interface AutoIdState {
  current: string
  lastSuggested: string
}

/** 选择变化时应用一次推导，返回新状态（不可变，便于测试）。 */
export function applySelection(state: AutoIdState, name: string): AutoIdState {
  const candidate = idFromName(name)
  if (!/^[A-Z]/.test(candidate)) {
    return state // 推不出合法 ID（空名 / 首字符非字母）：不要动用户已有输入
  }
  if (!shouldApplySuggestion(state.current, state.lastSuggested)) {
    return state
  }
  return { current: candidate, lastSuggested: candidate }
}
