/**
 * DeepSeek 计费（纯本地计算）。
 *
 * <p>约定：计费规则不进 System Prompt、不注册 Function Calling——规则只存在于本文件，
 * 由前端根据后端下发的 usage（tokens）本地计算，不产生任何额外 token 消耗。</p>
 *
 * <p>价格来源：https://api-docs.deepseek.com/zh-cn/quick_start/pricing
 * 单价单位：元 / 百万 tokens。价格调整只需改下面的 RATES 常量。</p>
 */

export type DeepSeekKind = 'flash' | 'pro'

/** 一次模型调用返回的用量（AgentScope 的 ChatUsage，或 OpenAI 风格的 snake_case）。 */
export interface UsageLike {
  inputTokens?: number
  outputTokens?: number
  /** 缓存命中的输入 tokens；旧接口/代理可能没有该字段 */
  cachedTokens?: number
  totalTokens?: number
  /** 兼容直接透传的 OpenAI 风格字段 */
  prompt_tokens?: number
  completion_tokens?: number
  prompt_cache_hit_tokens?: number
  prompt_cache_miss_tokens?: number
  prompt_tokens_details?: { cached_tokens?: number }
}

/** 单次调用费用明细。 */
export interface UsageCost {
  model: string
  isPeak: boolean
  hitTokens: number
  missTokens: number
  completionTokens: number
  inputCost: number
  outputCost: number
  totalCost: number
}

/** 会话累计（按费用维度拆开，便于悬浮展示明细）。 */
export interface SessionCost {
  total: number
  inputHit: number
  inputMiss: number
  output: number
  hitTokens: number
  missTokens: number
  completionTokens: number
  calls: number
}

interface Rate {
  /** 缓存命中输入 */
  hit: number
  /** 未命中输入 */
  miss: number
  /** 输出（含思考过程） */
  output: number
}

/**
 * 官方单价（元 / 百万 tokens，2026-11 查询）：
 * - deepseek-v4-flash：命中 0.02 / 未命中 1 / 输出 2
 * - deepseek-v4-pro：命中 0.025 / 未命中 3 / 输出 6
 *
 * <p>官方已取消峰谷差价，且 deepseek-chat / deepseek-reasoner 分别对应
 * v4-flash 的非思考 / 思考模式（同价）。这里仍保留 peak / idle 两组值：
 * 时段标签照常展示，后续若恢复峰谷折扣只需改这张表。</p>
 */
const RATES: Record<DeepSeekKind, { peak: Rate; idle: Rate }> = {
  flash: {
    peak: { hit: 0.02, miss: 1.0, output: 2.0 },
    idle: { hit: 0.02, miss: 1.0, output: 2.0 },
  },
  pro: {
    peak: { hit: 0.025, miss: 3.0, output: 6.0 },
    idle: { hit: 0.025, miss: 3.0, output: 6.0 },
  },
}

const MILLION = 1_000_000

/** 只有 deepseek 系列才计费；其他模型直接跳过。 */
export function isDeepSeekModel(modelName: string | undefined | null): boolean {
  return Boolean(modelName && /deepseek/i.test(modelName))
}

export function deepSeekKind(modelName: string): DeepSeekKind {
  // v4-pro 单独一档；deepseek-chat / deepseek-reasoner / v3 / r1 都归入 flash 档
  return /pro/i.test(modelName) ? 'pro' : 'flash'
}

/**
 * 高峰判断（Asia/Shanghai）：周一至周五 09:00-12:00、14:00-18:00。
 *
 * <p>官方当前已取消峰谷差价，该结果只用于界面上的时段标签；
 * 若之后恢复峰谷价，改 RATES 里的 peak / idle 即可生效。</p>
 *
 * <p>不依赖本地时区设置，用 Intl 显式指定时区，避免用户机器在东八区外算错时段。</p>
 */
export function isDeepSeekPeak(now: Date = new Date()): boolean {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    hour: '2-digit',
    hour12: false,
  })
  const parts = fmt.formatToParts(now)
  const weekday = parts.find((p) => p.type === 'weekday')?.value ?? 'Sun'
  // 部分实现 24 点会输出 "24"，取模归一
  const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? '0') % 24
  if (weekday === 'Sat' || weekday === 'Sun') return false
  return (hour >= 9 && hour < 12) || (hour >= 14 && hour < 18)
}

function toCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? Math.floor(value)
    : 0
}

/** 兼容两种入参：直接给 usage，或给带 usage 字段的响应对象。 */
function pickUsage(source: UsageLike | { usage?: UsageLike } | undefined | null): UsageLike {
  if (!source) return {}
  if ('usage' in source && source.usage) return source.usage
  return source as UsageLike
}

/**
 * 字段归一：AgentScope 用 camelCase（inputTokens/…），OpenAI 兼容接口用 snake_case
 * （prompt_tokens / prompt_cache_hit_tokens），这里统一成一组数字。
 */
function readTokens(usage: UsageLike): { input: number; hit: number; output: number } {
  const input = toCount(usage.inputTokens ?? usage.prompt_tokens)
  const output = toCount(usage.outputTokens ?? usage.completion_tokens)
  const hit = Math.min(
    toCount(
      usage.cachedTokens
      ?? usage.prompt_cache_hit_tokens
      ?? usage.prompt_tokens_details?.cached_tokens,
    ),
    input,
  )
  return { input, hit, output }
}

/**
 * 计算一次 DeepSeek 调用的费用。
 *
 * <p>缓存字段缺失时（旧版接口 / 代理），全部输入按"未命中"计价，不报错。</p>
 */
export function calculateDeepSeekCost(
  response: UsageLike | { usage?: UsageLike } | undefined | null,
  modelName: string,
  now: Date = new Date(),
): UsageCost {
  const usage = pickUsage(response)
  const kind = deepSeekKind(modelName)
  const isPeak = isDeepSeekPeak(now)
  const rate = RATES[kind][isPeak ? 'peak' : 'idle']

  const { input, hit, output } = readTokens(usage)
  const hitTokens = hit
  const missTokens = Math.max(input - hit, 0)
  const completionTokens = output

  const inputCost = (hitTokens / MILLION) * rate.hit + (missTokens / MILLION) * rate.miss
  const outputCost = (completionTokens / MILLION) * rate.output

  return {
    model: modelName,
    isPeak,
    hitTokens,
    missTokens,
    completionTokens,
    inputCost,
    outputCost,
    totalCost: inputCost + outputCost,
  }
}

export function emptySessionCost(): SessionCost {
  return {
    total: 0,
    inputHit: 0,
    inputMiss: 0,
    output: 0,
    hitTokens: 0,
    missTokens: 0,
    completionTokens: 0,
    calls: 0,
  }
}

/** 会话累计（纯函数，返回新对象，便于 Vue 响应式追踪）。 */
export function addSessionCost(base: SessionCost, cost: UsageCost): SessionCost {
  return {
    total: base.total + cost.totalCost,
    inputHit: base.inputHit + (cost.hitTokens / MILLION) * RATES[deepSeekKind(cost.model)][cost.isPeak ? 'peak' : 'idle'].hit,
    inputMiss: base.inputMiss + (cost.missTokens / MILLION) * RATES[deepSeekKind(cost.model)][cost.isPeak ? 'peak' : 'idle'].miss,
    output: base.output + cost.outputCost,
    hitTokens: base.hitTokens + cost.hitTokens,
    missTokens: base.missTokens + cost.missTokens,
    completionTokens: base.completionTokens + cost.completionTokens,
    calls: base.calls + 1,
  }
}

/** 金额统一保留 5 位小数，避免 0.000001 被截成 0.00。 */
export function formatCost(value: number): string {
  return `￥${value.toFixed(5)}`
}

/** 纯数字金额（前面已有时段/标题符号时用，避免重复货币符号）。 */
export function formatAmount(value: number): string {
  return value.toFixed(5)
}

/** 合并两次调用的费用（同一轮内多次模型调用需要累加）。 */
export function sumCost(previous: UsageCost | undefined, addition: UsageCost): UsageCost {
  if (!previous) return addition
  return {
    model: addition.model,
    isPeak: addition.isPeak,
    hitTokens: previous.hitTokens + addition.hitTokens,
    missTokens: previous.missTokens + addition.missTokens,
    completionTokens: previous.completionTokens + addition.completionTokens,
    inputCost: previous.inputCost + addition.inputCost,
    outputCost: previous.outputCost + addition.outputCost,
    totalCost: previous.totalCost + addition.totalCost,
  }
}

export function formatTokens(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}k` : String(value)
}
