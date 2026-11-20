/**
 * 镜像就绪视图的纯展示逻辑自校验（零依赖，node --experimental-strip-types 直接跑）。
 *
 * <p>回归背景：退避窗口与"已就绪"混在一起时，前端很容易把 `retryInSeconds=0` 当成
 * "已经好了"，从而在真正失败时给出绿色结论。这里锁住三条：状态只认 status、
 * 只有 FAILED 才显示重试倒计时、分钟级换算向上取整（"还要等 61 秒"要说约 2 分钟）。
 */
import { failedCount, retryText, sourceText, statusLabel, statusTagType } from './imageReadiness'

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

function assert(cond: boolean, message: string): void {
  if (!cond) throw new Error(message)
}

test('READY 就是已就绪，不看 retryInSeconds', () => {
  const row = { status: 'READY' as const, retryInSeconds: 0 }
  assert(statusLabel(row) === '已就绪', 'READY 应显示已就绪')
  assert(statusTagType(row) === 'success', 'READY 应为成功色')
  assert(retryText(row) === '—', 'READY 不显示重试倒计时')
})

test('FAILED 即使 retryInSeconds=0 也仍是失败', () => {
  const row = { status: 'FAILED' as const, retryInSeconds: 0 }
  assert(statusLabel(row) === '失败', 'FAILED 不能因为可立即重试就显示已就绪')
  assert(statusTagType(row) === 'danger', 'FAILED 应为危险色')
  assert(retryText(row) === '—', '可立即重试时不给倒计时')
})

test('退避中按分钟向上取整', () => {
  assert(retryText({ status: 'FAILED', retryInSeconds: 45 }) === '45 秒后可重试', '不足 1 分钟按秒说')
  assert(retryText({ status: 'FAILED', retryInSeconds: 61 }) === '约 2 分钟后重试', '61s 说约 2 分钟')
  assert(retryText({ status: 'FAILED', retryInSeconds: 600 }) === '约 10 分钟后重试', '10 分钟')
})

test('来源列区分本机与镜像源', () => {
  assert(sourceText({ source: 'local' }) === '本机已有', 'local 说人话')
  assert(sourceText({ source: 'localhost:5000' }) === 'localhost:5000', '缓存源原样显示')
  assert(sourceText({ source: null }) === '—', '没有来源时用占位符')
})

test('失败计数只数 FAILED', () => {
  const rows = [
    { status: 'READY' as const, retryInSeconds: 0, source: 'local', host: 'h', image: 'a',
      digest: null, attempts: 0, lastError: null, updatedAt: 0 },
    { status: 'FAILED' as const, retryInSeconds: 30, source: null, host: 'h', image: 'b',
      digest: null, attempts: 2, lastError: 'boom', updatedAt: 0 }
  ]
  assert(failedCount(rows) === 1, '只有一条失败')
  assert(failedCount([]) === 0, '空列表为 0')
})

if (failed > 0) {
  console.error(`❌ ${failed} 条失败 / ${passed + failed} 条`)
  process.exit(1)
}
console.log(`✓ imageReadiness：${passed} 条断言通过`)
