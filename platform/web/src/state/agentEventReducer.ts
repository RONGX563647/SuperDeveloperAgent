import type {
  AgentEvent, AgentProjection, ChatMessage, RunStatus, StreamingTurn, ToolActivity,
  UsageCost,
} from '../types'
import {
  addSessionCost, calculateDeepSeekCost, emptySessionCost, isDeepSeekModel,
} from '../utils/deepseekPricing'
import type { UsageLike } from '../utils/deepseekPricing'

/**
 * 事件 reducer（参考 ruoyi-copilot 的 harnessEventReducer.ts 移植）：
 *
 * - 纯函数：给定投影 + 事件 → 新投影；同一事件序列重放必然得到同一投影（幂等），
 *   这是刷新后从服务端重放事件重建视图的前提；
 * - sequence 单调过滤：乱序/迟到事件直接丢弃；
 * - ChatMessage 保持有序列表（DOM 顺序 = 事件顺序），
 *   流式正文通过 streamingTurn（effectId 游标）续写，工具调用打断了游标就另起一块；
 * - pendingChangeId 由后端 change.created 事件驱动，替代在文本里正则抠 `chg-…` 单号。
 */

const knownStatuses: RunStatus[] = [
  'QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_INPUT',
  'SUSPENDED', 'COMPLETED', 'FAILED', 'CANCELLED',
]

export function emptyAgentProjection(): AgentProjection {
  return {
    lastSequence: 0,
    messages: [],
    streamingTurn: null,
    tools: {},
    status: null,
    pendingChangeId: null,
    sessionCost: emptySessionCost(),
  }
}

function stringData(event: AgentEvent, key: string): string | undefined {
  const value = event.payload[key]
  return typeof value === 'string' && value ? value : undefined
}

function runStatus(value: unknown): RunStatus | null {
  return typeof value === 'string' && (knownStatuses as string[]).includes(value)
    ? value as RunStatus
    : null
}

/** 工具名 → 中文展示名（与参考项目 ToolCallCard 的映射保持一致）。 */
const toolDisplayNames: Record<string, string> = {
  apply_patch: '修改文件',
  shell_command: '运行命令',
  web__run: '检索资料',
  read_file: '读取文件',
  write_file: '写入文件',
  execute_process: '执行进程',
  project_info: '读取项目信息',
  list_envs: '列出环境',
  env_summary: '检查环境变量',
  env_var_probe: '校验变量指向',
  env_runtime_status: '查看应用运行状态',
  app_logs: '读取应用日志',
  port_check: '检查端口占用',
  grep: '检索仓库文本',
  release_trace: '追溯发布链路',
  env_mapping_draft: '生成环境绑定',
  deploy_env_status: '读取固化脚本',
  deploy_run: '触发部署',
  deploy_save_recipe: '固化部署脚本',
  ci_list_pipelines: '列出流水线',
  git_status: '查看仓库状态',
  git_commits: '查看提交历史',
  git_diff: '对比提交差异',
  list_files: '浏览项目文件',
  ci_run: '触发构建',
  ci_status: '查询运行状态',
  ci_cancel: '取消运行',
  ci_history: '查询运行历史',
  read_run_log: '读取运行日志',
  wait_run: '等待运行结束',
  list_actions: '列出动作',
  plan_action: '规划动作',
  run_action: '执行动作',
  request_confirmation: '发起变更确认',
}

/**
 * 后端 AgentEventMapper 直接下发 AgentScope 事件类名（如 TextBlockDeltaEvent），
 * 这里归一化成 reducer 认识的稳定事件名 + 归一化 payload。
 *
 * <p>两个关键点：
 * <ul>
 *   <li>{@code payload.id} 是每条事件各自的唯一 id，不能当流式游标——否则每个增量都会
 *       重置正文，最终只剩最后一小段；正文游标用 {@code replyId:blockId}（一次模型调用一段正文）；</li>
 *   <li>工具名在 ToolCallStartEvent 里叫 toolCallName；工具参数/结果是碎片增量，不能当正文渲染。</li>
 * </ul>
 */
function normalizeEvent(event: AgentEvent): AgentEvent {
  const p = event.payload
  const replyId = typeof p.replyId === 'string' ? p.replyId : ''
  const blockId = typeof p.blockId === 'string' ? p.blockId : ''
  switch (event.type) {
    case 'TextBlockDeltaEvent':
      return {
        ...event,
        type: 'text.delta',
        payload: { ...p, effectId: `${replyId}:${blockId || 'text'}` },
      }
    case 'ThinkingBlockDeltaEvent':
      return {
        ...event,
        type: 'assistant.thinking.delta',
        payload: { ...p, effectId: `${replyId}:thinking` },
      }
    case 'ToolCallStartEvent':
      return { ...event, type: 'tool.started', payload: { ...p, name: p.toolCallName } }
    case 'ToolCallDeltaEvent':
    case 'ToolCallEndEvent':
      return { ...event, type: 'tool.arguments' }
    case 'ToolResultTextDeltaEvent':
      return { ...event, type: 'tool.result.delta' }
    case 'ToolResultEndEvent':
      return {
        ...event,
        type: 'tool.completed',
        payload: { ...p, name: p.toolCallName, ...(p.state === 'success' ? {} : { error: true }) },
      }
    // 每次模型调用结束都会带 usage（inputTokens/outputTokens/cachedTokens）。
    // 归一化成 model.usage，由 reducer 本地算价；模型名由后端 DTO 注入。
    case 'ModelCallEndEvent':
      return {
        ...event,
        type: 'model.usage',
        payload: { model: p.model, usage: p.usage },
      }
    default:
      return event
  }
}

/** 切换流式游标：旧正文先落盘，避免新一段正文把它覆盖掉。 */
function switchTurn(projection: AgentProjection, effectId: string): StreamingTurn {
  const currentTurn = projection.streamingTurn
  if (currentTurn && currentTurn.effectId !== effectId) {
    if (currentTurn.text || currentTurn.thinking) {
      pushMessage(projection, 'assistant', currentTurn.text, undefined, undefined,
        currentTurn.thinking, currentTurn.cost)
    }
    projection.streamingTurn = null
  }
  if (!projection.streamingTurn) {
    projection.streamingTurn = { effectId, text: '', thinking: '', completed: false }
  }
  return projection.streamingTurn
}

export function toolDisplayName(toolName: string): string {
  return toolDisplayNames[toolName] ?? toolName.replaceAll('_', ' ')
}

function pushMessage(
  projection: AgentProjection,
  role: ChatMessage['role'],
  text: string,
  label?: string,
  toolCallId?: string,
  thinking?: string,
  cost?: UsageCost,
): void {
  projection.messages.push({
    id: `seq-${projection.lastSequence}-${projection.messages.length}`,
    role,
    text,
    thinking: thinking || undefined,
    label,
    toolCallId,
    cost,
    at: Date.now(),
  })
}

/** 同一轮可能连续调用多次模型（工具循环），费用要累加而不是互相覆盖。 */
function mergeCost(previous: UsageCost | undefined, addition: UsageCost): UsageCost {
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

export function reduceAgentEvent(current: AgentProjection, rawEvent: AgentEvent): AgentProjection {
  if (rawEvent.sequence <= current.lastSequence) return current
  const event = normalizeEvent(rawEvent)

  const next: AgentProjection = {
    ...current,
    messages: [...current.messages],
    tools: { ...current.tools },
    streamingTurn: current.streamingTurn ? { ...current.streamingTurn } : null,
    lastSequence: event.sequence,
  }

  // 流式游标：只认归一化后的 effectId（payload.id 每事件唯一，不能当游标）
  const effectId = stringData(event, 'effectId') ?? 'current'

  switch (event.type) {
    // ---- 流式正文/思考增量：effectId 游标续写，工具事件打断后另起一块 ----
    case 'assistant.text.delta':
    case 'text.delta':
    case 'message.delta': {
      const delta = stringData(event, 'delta')
        ?? stringData(event, 'text')
        ?? stringData(event, 'content')
        ?? ''
      if (!delta) break
      switchTurn(next, effectId).text += delta
      break
    }
    case 'assistant.thinking.delta': {
      const delta = stringData(event, 'delta') ?? ''
      if (!delta) break
      switchTurn(next, effectId).thinking += delta
      break
    }
    case 'assistant.completed':
    case 'message.completed': {
      if (next.streamingTurn) {
        // 落盘成一条 assistant 消息（思考过程一并保留，供折叠查看），清空流式游标
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）',
          undefined, undefined, next.streamingTurn.thinking, next.streamingTurn.cost)
        next.streamingTurn = null
      }
      break
    }

    // ---- 工具调用 ----
    case 'tool.started':
    case 'assistant.tool.complete': {
      // 工具打断正文游标：先把已积累的流式正文/思考落盘，避免静默丢失
      if (next.streamingTurn && (next.streamingTurn.text || next.streamingTurn.thinking)) {
        pushMessage(next, 'assistant', next.streamingTurn.text, undefined, undefined,
          next.streamingTurn.thinking, next.streamingTurn.cost)
      }
      const toolCallId = stringData(event, 'toolCallId')
        ?? stringData(event, 'tool')
        ?? `tool-${event.sequence}`
      const name = stringData(event, 'name')
        ?? stringData(event, 'tool')
        ?? stringData(event, 'toolName')
        ?? 'unknown_tool'
      next.tools[toolCallId] = {
        toolCallId,
        toolName: name,
        // 入参主体由 tool.arguments 增量补齐；start 事件偶尔会带全量，两者都要保
        arguments: stringData(event, 'arguments') ?? next.tools[toolCallId]?.arguments,
        status: 'RUNNING',
      }
      pushMessage(next, 'step', toolDisplayName(name), '工具调用', toolCallId)
      next.streamingTurn = null
      break
    }
    case 'tool.completed':
    case 'tool.finished': {
      const toolCallId = stringData(event, 'toolCallId')
        ?? stringData(event, 'tool')
        ?? `tool-${event.sequence}`
      const existing = next.tools[toolCallId]
      // AgentScope 的 ToolResultBlock.error() 会保留 state=RUNNING，结束事件仍报 success；
      // 真正的失败信号是返回文本前缀 "Error: "（框架统一约定），必须同时识别。
      const errorText = (existing?.resultText ?? '').trimStart().startsWith('Error:')
      const failed = event.payload.error === true
        || typeof event.payload.error === 'string'
        || errorText
      next.tools[toolCallId] = {
        toolCallId,
        toolName: existing?.toolName ?? stringData(event, 'name') ?? 'unknown_tool',
        arguments: existing?.arguments ?? stringData(event, 'arguments'),
        status: failed ? 'FAILED' : 'COMPLETED',
        code: stringData(event, 'code'),
        resultText: existing?.resultText,
      }
      // 若没有对应 started（后端骨架可能只发完成事件），补一条 step 消息
      if (!existing) {
        pushMessage(next, 'step', toolDisplayName(next.tools[toolCallId].toolName), '工具调用', toolCallId)
      }
      break
    }

    // ---- 变更单：由专用事件驱动，替代正则抠单号 ----
    case 'change.created': {
      const changeId = stringData(event, 'changeId')
      if (changeId) {
        next.pendingChangeId = changeId
        pushMessage(next, 'step', `变更单 ${changeId} 已创建，等待确认`, '变更确认')
      }
      break
    }
    case 'change.applied':
    case 'change.confirmed': {
      next.pendingChangeId = null
      break
    }

    // ---- 运行状态 ----
    case 'run.status':
    case 'run.state': {
      next.status = runStatus(event.payload.status)
      break
    }
    case 'run.completed': {
      next.status = 'COMPLETED'
      if (next.streamingTurn) {
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）',
          undefined, undefined, next.streamingTurn.thinking, next.streamingTurn.cost)
        next.streamingTurn = null
      }
      break
    }
    case 'run.failed': {
      next.status = 'FAILED'
      if (next.streamingTurn) {
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）',
          undefined, undefined, next.streamingTurn.thinking, next.streamingTurn.cost)
        next.streamingTurn = null
      }
      pushMessage(next, 'error', stringData(event, 'message') ?? '运行失败')
      break
    }
    case 'run.cancelled': {
      next.status = 'CANCELLED'
      break
    }

    // ---- 模型用量：本地算价（不调模型、不进 Prompt）----
    case 'model.usage': {
      const model = stringData(event, 'model') ?? ''
      // 条件触发：非 deepseek 系列直接跳过，不产生任何费用展示
      if (!isDeepSeekModel(model)) break
      const usage = event.payload.usage as UsageLike | undefined
      if (!usage) break
      const cost = calculateDeepSeekCost(usage, model)
      if (next.streamingTurn) {
        next.streamingTurn.cost = mergeCost(next.streamingTurn.cost, cost)
      } else {
        const lastAssistant = [...next.messages].reverse()
          .find((message) => message.role === 'assistant')
        if (lastAssistant) {
          lastAssistant.cost = mergeCost(lastAssistant.cost, cost)
        } else {
          pushMessage(next, 'assistant', '', undefined, undefined, undefined, cost)
        }
      }
      next.sessionCost = addSessionCost(next.sessionCost, cost)
      break
    }

    // ---- 错误帧（event:error 或 type === 'error'）----
    case 'error': {
      const message = stringData(event, 'message')
        ?? stringData(event, 'msg')
        ?? '对话流异常'
      pushMessage(next, 'error', message)
      break
    }

    // ---- 工具参数/结果碎片：属于工具卡片，不能并入正文 ----
    case 'tool.result.delta': {
      // 工具返回文本流式累积到工具卡片上：这是"这一步实际拿到了什么"的唯一证据，
      // 丢弃它会让执行过程只剩工具名（部署时看不出 runId / 失败原因）。
      const toolCallId = stringData(event, 'toolCallId')
      const delta = stringData(event, 'delta') ?? ''
      if (!toolCallId || !delta) break
      const tool = next.tools[toolCallId] ?? {
        toolCallId,
        toolName: stringData(event, 'toolCallName') ?? 'unknown_tool',
        status: 'RUNNING' as const,
      }
      const resultText = (tool.resultText ?? '') + delta
      next.tools[toolCallId] = {
        ...tool,
        // 只保留尾部：工具输出可能很长，界面展示"最新证据"即可
        resultText: resultText.length > 8000 ? resultText.slice(-8000) : resultText,
      }
      break
    }
    case 'tool.arguments': {
      // 工具入参是碎片增量（ToolCallDeltaEvent.delta），必须按 toolCallId 累积；
      // 之前这里直接 break，导致真实会话里所有工具的 arguments 都是空的。
      const toolCallId = stringData(event, 'toolCallId')
      const delta = stringData(event, 'delta') ?? ''
      if (!toolCallId || !delta) break
      const tool = next.tools[toolCallId] ?? {
        toolCallId,
        toolName: stringData(event, 'toolCallName') ?? 'unknown_tool',
        status: 'RUNNING' as const,
      }
      next.tools[toolCallId] = { ...tool, arguments: (tool.arguments ?? '') + delta }
      break
    }

    // ---- 兜底：AgentEventMapper 骨架仍会发类名事件（如 ReplyEvent）----
    default: {
      // 尽力提取文本增量（参考项目 extractText 的 key 集合）
      const delta = ['text', 'delta', 'content', 'message']
        .map((key) => stringData(event, key))
        .find(Boolean)
      if (delta) switchTurn(next, effectId).text += delta
      break
    }
  }

  return next
}

