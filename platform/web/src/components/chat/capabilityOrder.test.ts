import { orderCapabilities } from './capabilityOrder'

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

function equal<T>(actual: T, expected: T, hint = ''): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)} ${hint}`)
  }
}

const list = [
  { key: 'a', label: 'Gamma' },
  { key: 'b', label: 'Alpha' },
  { key: 'c', label: 'Beta' },
]

test('默认排序把已启用项置顶，组内保持原顺序', () => {
  equal(orderCapabilities(list, (k) => k === 'c' || k === 'a', 'default').map((x) => x.key),
    ['a', 'c', 'b'])
})

test('全部启用时顺序不变', () => {
  equal(orderCapabilities(list, () => true, 'default').map((x) => x.key), ['a', 'b', 'c'])
})

test('全部停用时顺序不变', () => {
  equal(orderCapabilities(list, () => false, 'default').map((x) => x.key), ['a', 'b', 'c'])
})

test('按名称排序且不修改原数组', () => {
  const input = [...list]
  equal(orderCapabilities(input, () => true, 'name').map((x) => x.key), ['b', 'c', 'a'])
  equal(input.map((x) => x.key), ['a', 'b', 'c'])
})

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) {
  ;(globalThis as { process?: { exitCode?: number } }).process!.exitCode = 1
}
