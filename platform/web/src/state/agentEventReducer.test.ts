/**
 * reducer 自校验脚本（零依赖，替代 vitest：环境无法安装）。
 *
 * 运行：node --experimental-strip-types src/state/agentEventReducer.test.ts
 * （Node 22+ 原生支持 TS 类型剥离；或用 tsx 运行）
 */
import { emptyAgentProjection, reduceAgentEvent, toolDisplayName } from './agentEventReducer'
import type { AgentEvent } from '../types'

let passed = 0
let failed = 0

function check(name: string, condition: boolean, detail = ''): void {
  if (condition) {
    passed++
    console.log(`  ✓ ${name}`)
  } else {
    failed++
    console.error(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

function ev(type: string, sequence: number, payload: Record<string, unknown> = {}): AgentEvent {
  return { type, sequence, payload }
}

// 1. 乱序/重复事件按 sequence 单调过滤
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('text.delta', 3, { text: 'abc' }))
  p = reduceAgentEvent(p, ev('text.delta', 2, { text: 'should-drop' }))
  check('乱序过滤 lastSequence=3', p.lastSequence === 3)
  check('乱序过滤 正文不被覆盖', p.streamingTurn?.text === 'abc')
}

// 2. 流式正文增量续写并落盘
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('text.delta', 1, { text: '你好' }))
  p = reduceAgentEvent(p, ev('text.delta', 2, { text: '，世界' }))
  check('流式续写', p.streamingTurn?.text === '你好，世界')
  p = reduceAgentEvent(p, ev('message.completed', 3))
  check('completed 落盘', p.streamingTurn === null
    && p.messages.length === 1
    && p.messages[0].role === 'assistant'
    && p.messages[0].text === '你好，世界')
}

// 3. 工具打断正文：已有正文先落盘，另起一块
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('text.delta', 1, { text: '开始' }))
  p = reduceAgentEvent(p, ev('tool.started', 2, { tool: 'shell_command', toolCallId: 't-1' }))
  p = reduceAgentEvent(p, ev('tool.completed', 3, { toolCallId: 't-1' }))
  p = reduceAgentEvent(p, ev('text.delta', 4, { text: '结束' }))
  p = reduceAgentEvent(p, ev('message.completed', 5))
  check('打断顺序 roles', JSON.stringify(p.messages.map((m) => m.role)) === JSON.stringify(['assistant', 'step', 'assistant']),
    JSON.stringify(p.messages.map((m) => m.role)))
  check('打断文本保留', p.messages[0].text === '开始' && p.messages[2].text === '结束')
  check('工具状态', p.tools['t-1']?.status === 'COMPLETED' && p.tools['t-1']?.toolName === 'shell_command')
}

// 4. tool.completed 无 started 时补 step 消息
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('tool.completed', 1, { toolCallId: 't-9', name: 'read_file' }))
  check('补 step 消息', p.messages.length === 1
    && p.messages[0].role === 'step'
    && p.messages[0].text.includes('读取文件'))
}

// 5. change.created 驱动 pendingChangeId
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('change.created', 1, { changeId: 'chg-abc' }))
  check('change.created', p.pendingChangeId === 'chg-abc')
  p = reduceAgentEvent(p, ev('change.applied', 2))
  check('change.applied 清空', p.pendingChangeId === null)
}

// 6. error 帧
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('error', 1, { message: '模型 401' }))
  check('error 消息', p.messages.length === 1
    && p.messages[0].role === 'error'
    && p.messages[0].text === '模型 401')
}

// 7. run.failed
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('text.delta', 1, { text: '写到一半' }))
  p = reduceAgentEvent(p, ev('run.failed', 2, { message: '超时' }))
  check('run.failed 状态', p.status === 'FAILED' && p.streamingTurn === null)
  check('run.failed 落盘顺序', p.messages.at(-1)?.role === 'error' && p.messages.at(-2)?.role === 'assistant')
}

// 8. 兜底提取文本
{
  let p = emptyAgentProjection()
  p = reduceAgentEvent(p, ev('ReplyEvent', 1, { text: '骨架事件' }))
  check('兜底提取', p.streamingTurn?.text === '骨架事件')
}

// 9. 重放幂等
{
  const events = [
    ev('text.delta', 1, { text: 'a' }),
    ev('tool.started', 2, { tool: 'x', toolCallId: 't' }),
    ev('tool.completed', 3, { toolCallId: 't' }),
    ev('text.delta', 4, { text: 'b' }),
    ev('message.completed', 5),
  ]
  const once = events.reduce(reduceAgentEvent, emptyAgentProjection())
  const twice = events.reduce(reduceAgentEvent, emptyAgentProjection())
  const key = (p: typeof once) => JSON.stringify({
    m: p.messages.map((m) => [m.role, m.text]),
    t: p.tools,
    s: p.lastSequence,
  })
  check('重放幂等', key(once) === key(twice))
}

// 10. toolDisplayName
check('toolDisplayName 已知', toolDisplayName('apply_patch') === '修改文件')
check('toolDisplayName 未知', toolDisplayName('unknown_thing') === 'unknown thing')

// markdown 渲染自校验（顺便覆盖新渲染器）
import { renderMarkdown } from '../utils/markdown'
{
  const html = renderMarkdown('# 标题\n\n**粗体** and `code`\n\n```js\nconst x = 1 // 注释\n```\n')
  check('md 标题', html.includes('<h1>标题</h1>'))
  check('md 粗体', html.includes('<strong>粗体</strong>'))
  check('md 行内代码', html.includes('<code>code</code>'))
  check('md 代码块高亮', html.includes('tok-kw') && html.includes('tok-comment'))
  check('md 防注入', !renderMarkdown('<script>alert(1)</script>').includes('<script>'))
}

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
