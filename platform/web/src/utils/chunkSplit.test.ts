/**
 * 「部署后前端白屏」回归脚本（零依赖）。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/chunkSplit.test.ts
 *
 * 根因回顾：manualChunks 曾把 element-plus / vue / 其余拆成三块，
 * element-plus 与 @vue/runtime-core、@vueuse 循环引用 → 跨 chunk TDZ →
 * Vue 挂不上、部署产物白屏。这里锁死"所有第三方依赖必须同处一个 chunk"。
 */
import { manualChunkOf } from './chunkSplit.ts'

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

const vendorChunk = 'vendor'

// 1. 所有历史上被拆分的依赖，现在必须统一落到 vendor（不得再出现 element-plus / vue-vendor）
const thirdParty = [
  'node_modules/element-plus/es/index.mjs',
  'node_modules/@element-plus/icons-vue/dist/index.js',
  'node_modules/vue/dist/vue.runtime.esm-bundler.js',
  'node_modules/@vue/runtime-core/dist/runtime-core.esm-bundler.js',
  'node_modules/@vueuse/core/index.mjs',
  'node_modules/lodash-unified/import.js',
  'node_modules/axios/index.js',
  'node_modules/echarts/index.js',
  'C:/proj/web/node_modules/pinia/dist/pinia.mjs',
]
for (const id of thirdParty) {
  check(`第三方依赖归一到 vendor：${id.split('node_modules/')[1]}`,
    manualChunkOf(id) === vendorChunk, `实际=${manualChunkOf(id)}`)
}

// 2. 关键断言：不存在任何拆分的 vendor 子块名（白屏的直接触发条件）
const splitNames = new Set(thirdParty.map((id) => manualChunkOf(id)).filter(Boolean))
check('第三方依赖只产生 1 个 chunk 名（无跨 chunk 循环）',
  splitNames.size === 1 && splitNames.has(vendorChunk),
  `实际 chunk 集合=${[...splitNames].join(',')}`)

// 3. 业务源码不进 vendor（保持按需分包与缓存收益）
const appSources = [
  'src/main.ts',
  'src/views/PipelineView.vue',
  'src/api/http.ts',
  'E:/code/proj/src/utils/chunkSplit.ts',
]
for (const id of appSources) {
  check(`业务源码不分包：${id.split('/').slice(-1)[0]}`, manualChunkOf(id) === undefined)
}

// 4. 边界：路径里出现 node_modules 字样但为业务文件——按现有规则归入 vendor（与 vite 行为一致），
//    这条仅确保函数不抛异常、返回值稳定
check('异常输入不抛错', (() => {
  try {
    manualChunkOf('')
    manualChunkOf('node_modules')
    return true
  } catch {
    return false
  }
})())

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
