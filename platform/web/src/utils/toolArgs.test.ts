/**
 * 工具入参表达口径的回归脚本（零依赖，替代 vitest）。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/toolArgs.test.ts
 */
import { describeToolArgs, summarizeToolArgs } from './toolArgs.ts'

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

// 1. 有入参：原样给出，不告警
{
  const view = describeToolArgs('{"pattern":"x"}', false, 'COMPLETED')
  check('有入参 -> present', view.kind === 'present', JSON.stringify(view))
  check('有入参不告警', view.warn === false)
  check('有入参原样保留', view.text === '{"pattern":"x"}')
}

// 2. 后端明确不可用：告警 + 写明「不等于无参数」
{
  const view = describeToolArgs('', true, 'COMPLETED')
  check('不可用 -> unavailable', view.kind === 'unavailable', JSON.stringify(view))
  check('不可用要告警', view.warn === true)
  check('不可用文案区分于无参数', view.text.includes('不等于该工具无参数'), view.text)
}

// 3. 运行中且暂无参数：属于临时态，不能写成「无参数」
{
  const view = describeToolArgs('', false, 'RUNNING')
  check('运行中空参 -> pending', view.kind === 'pending', JSON.stringify(view))
  check('pending 不告警', view.warn === false)
}

// 4. 完成且空参：真的是无参工具
{
  const view = describeToolArgs('', false, 'COMPLETED')
  check('完成后空参 -> none', view.kind === 'none', JSON.stringify(view))
  check('none 文案是无参数', view.text === '无参数', view.text)
}

// 5. 空对象/空数组视作没参数（模型常给 {}），但仍要看是否被标记不可用
{
  check('空对象不算有参', describeToolArgs('{}', false, 'COMPLETED').kind === 'none')
  check('空数组不算有参', describeToolArgs('[]', false, 'COMPLETED').kind === 'none')
  check('空对象 + 不可用标记 -> unavailable',
    describeToolArgs('{}', true, 'COMPLETED').kind === 'unavailable')
}

// 6. 摘要行：截断、不可用、pending 各自的口径
{
  const long = JSON.stringify({ a: 'x'.repeat(200) })
  const summary = summarizeToolArgs(long, false, 'COMPLETED', 64)
  check('摘要截断到 64', summary.length <= 65, `${summary.length}`)
  check('摘要带省略号', summary.endsWith('…'), summary)

  check('不可用摘要写明', summarizeToolArgs('', true, 'COMPLETED') === '入参不可用')
  check('pending 摘要为空', summarizeToolArgs('', false, 'RUNNING') === '')
  check('无参摘要为空', summarizeToolArgs('', false, 'COMPLETED') === '')
  check('摘要压成一行',
    !summarizeToolArgs('{"a":\n 1}', false, 'COMPLETED').includes('\n'))
}

// 7. 边界：undefined 全部安全
{
  const view = describeToolArgs(undefined, undefined, undefined)
  check('undefined 安全', view.kind === 'none', JSON.stringify(view))
}

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
