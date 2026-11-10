/**
 * DeepSeek 计费（纯本地计算）。
 *
 * <p>约定：计费规则不进 System Prompt、不注册 Function Calling——规则只存在于本文件，
 * 由前端根据后端下发的 usage（tokens）本地计算，不产生任何额外 token 消耗。</p>
 *
 * <p>价格来源：https://api-docs.deepseek.com/zh-cn/quick_start/pricing
 * 单价单位：元 / 百万 tokens。价格调整只需改下面的 RATES 常量。</p>
 */

export type DeepSeekKind = 'chat' | 'reasoner'

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

const RATES: Record<DeepSeekKind, { peak: Rate; idle: Rate }> = {
  chat: {
    peak: { hit: 0.1, miss: 2.0, output: 8.0 },
    idle: { hit: 0.1, miss: 1.0, output: 4.0 },
  },
  reasoner: {
    peak: { hit: 0.1, miss: 4.0, output: 16.0 },
    idle: { hit: 0.1, miss: 1.0, output: 8.0 },
  },
}

const MILLION = 1_000_000

/** 只有 deepseek 系列才计费；其他模型直接跳过。 */
export function isDeepSeekModel(modelName: string | undefined | null): boolean {
  return Boolean(modelName && /deepseek/i.test(modelName))
}

export function deepSeekKind(modelName: string): DeepSeekKind {
  // deepseek-reasoner / deepseek-r1 / 带 reasoner 的代理别名都按 R1 计价
  return /reasoner|r1/i.test(modelName) ? 'reasoner' : 'chat'
}

/**
 * 高峰判断（Asia/Shanghai）：周一至周五 09:00-12:00、14:00-18:00。
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
  return `¥${value.toFixed(5)}`
}

export function formatTokens(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}k` : String(value)
}
