/**
 * 环境卡片区回归（零依赖，node --experimental-strip-types 直接跑）。
 *
 * BUG：新增「最近保留的镜像」面板时，把原属于环境网格的 `v-else` 留在了镜像
 * section 上——`v-else` 与紧邻的 `v-if="retainedImages.length"` 配对，于是只要有
 * 保留镜像，环境卡片网格就永远不渲染。截图表现为：统计显示「3 个环境 · 1 个运行中」，
 * 下方却整片空白，只有镜像面板正常显示。
 *
 * 这里锁住：环境网格有自己的条件、镜像面板也有自己的条件，二者互斥关系不存在，
 * 且环境网格仍排在镜像面板之前（保持原有视觉顺序）。
 */
import { readFileSync } from 'node:fs'

const source = readFileSync(
  new URL('./DeploymentMatrix.vue', import.meta.url), 'utf-8')

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

test('环境卡片网格有独立的 v-if="hasRows"', () => {
  expect(source.includes('<div v-if="hasRows" class="dv-grid">'),
    '环境网格必须显式按 hasRows 渲染，不能依赖 v-else')
})

test('镜像面板有独立的 v-if="retainedImages.length"，不再承接 v-else', () => {
  expect(source.includes('<section v-if="retainedImages.length" class="dv-images">'),
    '镜像面板应有自己的 v-if')
  expect(!source.includes('<section v-else'),
    '镜像面板不得使用 v-else（会与环境空态/网格错误配对）')
  expect(!source.includes('<div v-else class="dv-grid">'),
    '环境网格不得再用 v-else——这是导致卡片空白的原始 bug')
})

test('环境网格排在镜像面板之前', () => {
  const grid = source.indexOf('<div v-if="hasRows" class="dv-grid">')
  const images = source.indexOf('<section v-if="retainedImages.length" class="dv-images">')
  expect(grid >= 0 && images >= 0 && grid < images,
    '环境卡片区应位于「最近保留的镜像」之前，恢复原有视觉顺序')
})

test('环境空态仍由 hasRows 决定，与镜像是否为空无关', () => {
  expect(source.includes('<div v-if="!hasRows && !loading" class="dv-empty">'),
    '无部署产物时仍应显示空态提示')
})

console.log(`\nDeploymentMatrix: ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
