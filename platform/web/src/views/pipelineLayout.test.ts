/**
 * 流水线运行详情布局自校验（零依赖，node --experimental-strip-types 直接跑）。
 *
 * 回归背景：.run-detail 曾经是 overflow:hidden，产物/镜像面板一多就把日志区
 * 挤出可视区且无法滚动，表现为「日志后面看不见」。这里锁住三个关键约束：
 * 详情列可纵向滚动、日志区有保底高度、日志内部自己滚。
 */
import { readFileSync } from 'node:fs'

/** 只取 <style scoped> 之后的 CSS，并去掉注释（注释里会提到被禁用的写法）。 */
function cssOf(file: URL): string {
  const source = readFileSync(file, 'utf-8')
  const start = source.indexOf('<style scoped>')
  return (start >= 0 ? source.slice(start) : '').replace(/\/\*[\s\S]*?\*\//g, '')
}

const style = cssOf(new URL('./PipelineView.vue', import.meta.url))
const logStyle = cssOf(new URL('../components/pipeline/RunLog.vue', import.meta.url))

let passed = 0
let failed = 0

function test(name: string, run: () => void): void {
  try {
    run()
    passed += 1
  } catch (e) {
    failed += 1
    console.error(`❌ ${name}: ${e instanceof Error ? e.message : String(e)}`)
  }
}

/** 取某条 CSS 规则的声明块（跨两个组件的 scoped 样式里找）。 */
function rule(selector: string): string {
  const blocks: string[] = []
  for (const css of [style, logStyle]) {
    let from = 0
    while (true) {
      const at = css.indexOf(`${selector} {`, from)
      if (at < 0) break
      const end = css.indexOf('}', at)
      blocks.push(css.slice(at + selector.length, end))
      from = end
    }
  }
  if (!blocks.length) throw new Error(`找不到规则 ${selector}`)
  return blocks.join('\n')
}

function assert(ok: boolean, message: string): void {
  if (!ok) throw new Error(message)
}

test('运行详情列改为纵向滚动而不是裁掉内容', () => {
  const detail = rule('.run-detail')
  assert(!/overflow:\s*hidden/.test(detail), `.run-detail 不能用 overflow:hidden：${detail}`)
  assert(/overflow-y:\s*auto/.test(detail), `.run-detail 需要 overflow-y:auto：${detail}`)
})

test('日志区在详情列里有保底高度，不会被挤成一条缝', () => {
  const log = rule('.run-detail :deep(.run-log)')
  const minHeight = /min-height:\s*(\d+)px/.exec(log)
  assert(!!minHeight, `.run-log 需要固定 min-height：${log}`)
  assert(Number(minHeight![1]) >= 240, `.run-log 的 min-height 过小：${minHeight![1]}px`)
})

test('日志滚动区自身可滚', () => {
  const scroll = rule('.log-scroll')
  assert(/overflow:\s*auto/.test(scroll), `.log-scroll 需要 overflow:auto：${scroll}`)
})

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) {
  ;(globalThis as { process?: { exitCode?: number } }).process!.exitCode = 1
}
