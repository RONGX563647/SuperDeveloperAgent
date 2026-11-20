/**
 * 环境状态条展开状态回归（零依赖，node --experimental-strip-types 直接跑）。
 *
 * BUG-26 / BUG-53：原实现把展开状态写进全局单 key `agent.envBar.open`，
 * 用户展开过一次后每次进对话页都自动展开，且除精确点中折叠按钮外无法关闭；
 * 叠加切环境先清空数据导致的三段高度跳变，观感是「环境条一直弹出、画面一直抖」。
 *
 * 这里锁住四个约束：
 * 1. localStorage key 按「项目 · 环境」隔离；
 * 2. ChatView 显式传 :default-open="false"（默认折叠有落点）；
 * 3. 切换项目/环境时不再清空旧数据（不产生「就绪→空→就绪」跳变）；
 * 4. 缺变量/刷新中的健康态判定不抢在旧数据之上闪烁。
 */
import { readFileSync } from 'node:fs'

const barSource = readFileSync(
  new URL('./EnvironmentStatusBar.vue', import.meta.url), 'utf-8')
const chatSource = readFileSync(
  new URL('../../views/ChatView.vue', import.meta.url), 'utf-8')

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

function expect(cond: boolean, msg: string): void {
  if (!cond) throw new Error(msg)
}

test('展开状态 key 按 projectId+env 隔离', () => {
  expect(barSource.includes('agent.envBar.open.'), 'key 模板应带 projectId/env 前缀')
  expect(!/OPEN_KEY = 'agent\.envBar\.open'/.test(barSource),
    '不允许再出现全局单 key OPEN_KEY = agent.envBar.open')
  expect(barSource.includes('openKey(props.projectId, props.env)'),
    'loadOpen 与 toggle 都必须按组合取 key')
})

test('ChatView 显式传入 :default-open="false"', () => {
  const tag = chatSource.slice(chatSource.indexOf('<EnvironmentStatusBar'),
    chatSource.indexOf('/>', chatSource.indexOf('<EnvironmentStatusBar')))
  expect(tag.includes(':default-open="false"'),
    'EnvironmentStatusBar 必须显式 default-open=false（默认折叠）')
})

test('切换项目/环境时保留旧数据，就地刷新', () => {
  const watchBlock = barSource.slice(barSource.indexOf('watch([() => props.projectId'),
    barSource.indexOf('})', barSource.indexOf('watch([() => props.projectId')))
  expect(!watchBlock.includes('effective.value = null'),
    'watch 里不得清空 effective（旧摘要保留占位防跳变）')
  expect(!watchBlock.includes('check.value = null'),
    'watch 里不得清空 check')
  expect(watchBlock.includes('void load()'), 'watch 仍需触发刷新')
})

test('health 判定不再被 loading/failed 抢占旧数据', () => {
  const healthBlock = barSource.slice(barSource.indexOf("const health = computed"),
    barSource.indexOf('})', barSource.indexOf("const health = computed")))
  expect(healthBlock.includes("loading.value && !effective.value && !check.value"),
    '仅在无任何旧数据时才显示「读取中」')
  expect(healthBlock.includes("failed.value && !effective.value"),
    '读取失败仅在无旧数据时报 error（刷新失败保留旧摘要）')
})

test('env-pulse 只用于 streaming 场景，不常驻缺变量态', () => {
  expect(barSource.includes('.run.busy .dot'),
    '脉冲动画只能挂在 streaming 的 run 指示上')
  expect(!/\.is-error[^{]*\{[^}]*env-pulse/.test(barSource),
    'error 态不得挂无限脉冲动画')
})

console.log(`\nenvBarState: ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
