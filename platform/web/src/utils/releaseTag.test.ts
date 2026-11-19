/**
 * 发布标签展示口径的回归脚本（零依赖）。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/releaseTag.test.ts
 */
import { tagNeedsAttention, tagStateText } from './releaseTag.ts'

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

// 1. 四种真实状态各自有可读文案
check('PUSHED -> 已推送', tagStateText('PUSHED') === '标签已推送')
check('LOCAL -> 仅本地', tagStateText('LOCAL') === '标签仅本地')
check('FAILED -> 创建失败', tagStateText('FAILED') === '标签创建失败')
check('REJECTED -> 被拒绝', tagStateText('REJECTED') === '标签被拒绝')

// 2. 不该展示的情况不展示
check('SNAPSHOT 不展示', tagStateText('SNAPSHOT') === '')
check('undefined 不展示', tagStateText(undefined) === '')
check('空串不展示', tagStateText('') === '')

// 3. 未知值原样回显（后端加新状态时不至于空白）
check('未知值原样回显', tagStateText('WHATEVER') === 'WHATEVER')

// 4. 警示判据：只有 PUSHED 不算问题
check('PUSHED 不警示', tagNeedsAttention('PUSHED') === false)
check('LOCAL 警示', tagNeedsAttention('LOCAL') === true)
check('FAILED 警示', tagNeedsAttention('FAILED') === true)
check('REJECTED 警示', tagNeedsAttention('REJECTED') === true)
check('SNAPSHOT 不警示', tagNeedsAttention('SNAPSHOT') === false)
check('undefined 不警示', tagNeedsAttention(undefined) === false)

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
