/**
 * findChangeId 前后端同口径回归（BUG-31，零依赖 check 风格）。
 *
 * 背景：前端原来用「任意位置 + 严格 UUID」正则，后端 ChangeWire 用「整行匹配 +
 * chg-[A-Za-z0-9-]{8,64}」。两端口径不一致导致双向错误——前端会认后端不认的
 * （句子里的单号渲染出假确认卡），也会漏掉后端认的（非 UUID 形状的真单号不出卡）。
 *
 * 本文件使用后端 ChangeWireTest 的同一批样例断言同一结论，防止两端再漂移：
 *   platform/agent-domain/src/test/java/top/rongx/agent/domain/change/ChangeWireTest.java
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/api/chat.test.ts
 */
import { findChangeId } from './changeWire.ts'

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

const UUID_LIKE = 'chg-3f2c9a1e-7b4d-4c2a-9f10-2a1b3c4d5e6f'
const NON_UUID = 'chg-2be49350-cf48-4ce7-9520-7682ab5c09d0'
const SHORT_ID = 'chg-2026-1119-ab12' // 非 UUID，但符合 [A-Za-z0-9-]{8,64}

// —— 样例集 1：合法整行 → 前后端都认（对应 ChangeWireTest.lineThenExtractRoundTrips）——
check(
  '工具结果整行 changeId 被识别',
  findChangeId(
    `status: PENDING_USER_CONFIRMATION\nchangeId: ${UUID_LIKE}\nplanHash: abc`
  ) === UUID_LIKE
)
check(
  '非 UUID 形状的合法单号被识别（BUG-31 方向 B：真卡不再丢失）',
  findChangeId(`status: PENDING_USER_CONFIRMATION\nchangeId: ${SHORT_ID}\nplanHash: abc`) ===
    SHORT_ID
)
check(
  '行首允许缩进（后端正则 ^\\s* 同口径）',
  findChangeId(`planHash: abc\n   changeId: ${NON_UUID}  \nstatus: x`) === NON_UUID
)
check(
  'JSON 序列化转义换行（前端拿到的已是还原文本，多行照常匹配）',
  findChangeId(`status: PENDING\nchangeId: ${UUID_LIKE}\nplanHash: abc`) === UUID_LIKE
)

// —— 样例集 2：句子内单号 → 前后端都不认（对应 ChangeWireTest.onlyStandaloneLineIsAccepted）——
check(
  '句子里的单号不渲染确认卡（BUG-31 方向 A：不再出假卡）',
  findChangeId(`已完成，可参考 changeId: chg-11111111-2222 这条记录`) === null
)
check(
  '正文顺带提到单号不渲染确认卡',
  findChangeId(
    `已登记，很紧急，changeId: ${UUID_LIKE}，请尽快确认`
  ) === null
)
check('带行尾说明文字的不认（对应 ChangeWireTest.trailingTextOnSameLineIsRejected）', findChangeId(`changeId: ${UUID_LIKE}（仅供参考）`) === null)
check('非 chg- 前缀的不认', findChangeId('changeId: not-a-change') === null)
check('空串返回 null（后端 extract("") 同口径）', findChangeId('') === null)
check('null 安全', (findChangeId as unknown as (t: null) => string | null)(null) === null)

// —— 样例集 3：形状边界（[A-Za-z0-9-]{8,64} 长度约束）——
check('chg- 前缀后不足 8 位的不认', findChangeId('changeId: chg-1234567') === null)
check('恰好 8 位可认', findChangeId('changeId: chg-12345678') === 'chg-12345678')
check(
  '超过 64 位的不认（后端 {8,64} 上限同口径）',
  findChangeId(`changeId: chg-${'a'.repeat(65)}`) === null
)
check(
  '恰好 64 位可认',
  findChangeId(`changeId: chg-${'a'.repeat(64)}`) === `chg-${'a'.repeat(64)}`
)
check('大写字母与连字符可认（[A-Za-z0-9-] 同口径）', findChangeId('changeId: chg-AbC-XyZ-2026') === 'chg-AbC-XyZ-2026')
check('含下划线/点号的不认（后端字符集同口径）', findChangeId('changeId: chg-abc_def.xyz') === null)
check('多行文本里只有第一处整行生效', findChangeId(`x\nchangeId: ${UUID_LIKE}\nchangeId: ${SHORT_ID}`) === UUID_LIKE)

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
