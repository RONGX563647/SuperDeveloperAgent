#!/usr/bin/env node
/**
 * 前端零依赖测试运行器：自动发现 `src/**\/*.test.ts` 并逐个执行。
 *
 * 为什么不用 vitest：本仓库前端测试是 `check(name, cond)` 形式的零依赖脚本，
 * Node 22 的 `--experimental-strip-types` 直接跑 TS，`tools/ts-resolve.mjs`
 * 负责把无扩展名的相对导入补成 .ts。整套跑完 < 2s，不需要引入测试框架。
 *
 * 为什么不做手写清单：原来 `package.json` 与门禁各维护一份文件名清单，
 * 结果 8 个测试文件里只有 3～6 个真正被执行——新写的测试等于没跑。
 * 目录扫描一次几毫秒，换掉这类静默漏跑。
 */
import { readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const WEB = path.resolve(HERE, '..')
const SRC = path.join(WEB, 'src')

/** 递归收集测试文件（跳过依赖与产物目录）。 */
function discover(dir) {
  const found = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'dist') continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      found.push(...discover(full))
    } else if (entry.name.endsWith('.test.ts')) {
      found.push(path.relative(WEB, full).split(path.sep).join('/'))
    }
  }
  return found.sort()
}

const scripts = discover(SRC)
if (scripts.length === 0) {
  console.log('未发现 *.test.ts，跳过')
  process.exit(0)
}

let failed = 0
for (const script of scripts) {
  // stdio: inherit —— 让每个脚本自己的 ✓/✗ 行直接出现在输出里，失败时能定位到哪一条
  const result = spawnSync(
    process.execPath,
    ['--experimental-strip-types', '--import', './tools/ts-resolve.mjs', script],
    { cwd: WEB, stdio: 'inherit' },
  )
  if (result.status !== 0) {
    failed++
    console.error(`✗ ${script}`)
  }
}

if (failed > 0) {
  console.error(`\n${failed}/${scripts.length} 个测试文件失败`)
  process.exit(1)
}
console.log(`\n${scripts.length} 个测试文件全部通过`)
