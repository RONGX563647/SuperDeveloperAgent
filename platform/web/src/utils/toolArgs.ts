/**
 * 工具入参在界面上的表达口径。
 *
 * 为什么单独抽成函数：入参「缺失」有三种完全不同的原因，界面上却极易混成一种：
 *   1. 工具本来没有参数（如 workspace_snapshot）——正常；
 *   2. 有参数但还没到（流式累积中）——临时；
 *   3. 后端明确告知拿不到（argsUnavailable）——异常，需要排查。
 * 三者都显示成「无参数」时，第 3 种会被当成正常，问题就长期藏着——
 * 轨迹里参数看起来"缺失"却没人发现，正是这个原因。
 */

export type ToolArgsKind = 'present' | 'pending' | 'unavailable' | 'none'

export interface ToolArgsView {
  kind: ToolArgsKind
  /** 可直接放进界面的文案（pending 时为空，由调用方决定是否显示占位） */
  text: string
  /** 是否需要人注意（只有 unavailable 为 true） */
  warn: boolean
}

/**
 * 把工具活动折算成可展示的入参视图。
 *
 * @param raw 已累积的入参字符串（可能为空）
 * @param argsUnavailable 后端是否明确告知入参不可用
 * @param status 工具状态：RUNNING 时「还没参数」属于临时态
 */
export function describeToolArgs(
  raw: string | undefined,
  argsUnavailable: boolean | undefined,
  status: string | undefined,
): ToolArgsView {
  const trimmed = (raw ?? '').trim()
  if (trimmed && trimmed !== '{}' && trimmed !== '[]') {
    return { kind: 'present', text: trimmed, warn: false }
  }
  if (argsUnavailable === true) {
    return {
      kind: 'unavailable',
      text: '入参不可用（后端未提供）；这不等于该工具无参数',
      warn: true,
    }
  }
  if (status === 'RUNNING') {
    return { kind: 'pending', text: '', warn: false }
  }
  return { kind: 'none', text: '无参数', warn: false }
}

/** 摘要行用的一行版本：过长截断，不可用直接写明。 */
export function summarizeToolArgs(
  raw: string | undefined,
  argsUnavailable: boolean | undefined,
  status: string | undefined,
  limit = 64,
): string {
  const view = describeToolArgs(raw, argsUnavailable, status)
  if (view.kind === 'pending') return ''
  if (view.kind === 'unavailable') return '入参不可用'
  if (view.kind === 'none') return ''
  const compact = view.text.replace(/\s+/g, ' ')
  return compact.length > limit ? `${compact.slice(0, limit)}…` : compact
}
