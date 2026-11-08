import type { AgentEvent, AgentProjection, ChatMessage, RunStatus, ToolActivity } from '../types'

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
}

export function toolDisplayName(toolName: string): string {
  return toolDisplayNames[toolName] ?? toolName.replaceAll('_', ' ')
}

function pushMessage(projection: AgentProjection, role: ChatMessage['role'], text: string, label?: string): void {
  projection.messages.push({
    id: `seq-${projection.lastSequence}-${projection.messages.length}`,
    role,
    text,
    label,
    at: Date.now(),
  })
}

export function reduceAgentEvent(current: AgentProjection, event: AgentEvent): AgentProjection {
  if (event.sequence <= current.lastSequence) return current

  const next: AgentProjection = {
    ...current,
    messages: [...current.messages],
    tools: { ...current.tools },
    streamingTurn: current.streamingTurn ? { ...current.streamingTurn } : null,
    lastSequence: event.sequence,
  }

  const effectId = stringData(event, 'effectId')
    ?? (typeof event.payload.id === 'string' ? event.payload.id : undefined)

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
      const turn = next.streamingTurn?.effectId === (effectId ?? 'current')
        ? next.streamingTurn!
        : { effectId: effectId ?? 'current', text: '', thinking: '', completed: false }
      turn.text += delta
      next.streamingTurn = turn
      break
    }
    case 'assistant.thinking.delta': {
      const delta = stringData(event, 'delta') ?? ''
      const turn = next.streamingTurn?.effectId === (effectId ?? 'current')
        ? next.streamingTurn!
        : { effectId: effectId ?? 'current', text: '', thinking: '', completed: false }
      turn.thinking += delta
      next.streamingTurn = turn
      break
    }
    case 'assistant.completed':
    case 'message.completed': {
      if (next.streamingTurn) {
        // 落盘成一条 assistant 消息，清空流式游标
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）')
        next.streamingTurn = null
      }
      break
    }

    // ---- 工具调用 ----
    case 'tool.started':
    case 'assistant.tool.complete': {
      // 工具打断正文游标：先把已积累的流式正文落盘，避免静默丢失
      if (next.streamingTurn && next.streamingTurn.text) {
        pushMessage(next, 'assistant', next.streamingTurn.text)
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
        arguments: stringData(event, 'arguments'),
        status: 'RUNNING',
      }
      pushMessage(next, 'step', toolDisplayName(name), '工具调用')
      next.streamingTurn = null
      break
    }
    case 'tool.completed':
    case 'tool.finished': {
      const toolCallId = stringData(event, 'toolCallId')
        ?? stringData(event, 'tool')
        ?? `tool-${event.sequence}`
      const existing = next.tools[toolCallId]
      const failed = event.payload.error === true || typeof event.payload.error === 'string'
      next.tools[toolCallId] = {
        toolCallId,
        toolName: existing?.toolName ?? stringData(event, 'name') ?? 'unknown_tool',
        arguments: existing?.arguments ?? stringData(event, 'arguments'),
        status: failed ? 'FAILED' : 'COMPLETED',
        code: stringData(event, 'code'),
      }
      // 若没有对应 started（后端骨架可能只发完成事件），补一条 step 消息
      if (!existing) {
        pushMessage(next, 'step', toolDisplayName(next.tools[toolCallId].toolName), '工具调用')
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
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）')
        next.streamingTurn = null
      }
      break
    }
    case 'run.failed': {
      next.status = 'FAILED'
      if (next.streamingTurn) {
        pushMessage(next, 'assistant', next.streamingTurn.text || '（无正文）')
        next.streamingTurn = null
      }
      pushMessage(next, 'error', stringData(event, 'message') ?? '运行失败')
      break
    }
    case 'run.cancelled': {
      next.status = 'CANCELLED'
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

    // ---- 兜底：AgentEventMapper 骨架仍会发类名事件（如 ReplyEvent）----
    default: {
      // 尽力提取文本增量（参考项目 extractText 的 key 集合）
      const delta = ['text', 'delta', 'content', 'message']
        .map((key) => stringData(event, key))
        .find(Boolean)
      if (delta) {
        const turn = next.streamingTurn?.effectId === (effectId ?? 'current')
          ? next.streamingTurn!
          : { effectId: effectId ?? 'current', text: '', thinking: '', completed: false }
        turn.text += delta
        next.streamingTurn = turn
      }
      break
    }
  }

  return next
}

