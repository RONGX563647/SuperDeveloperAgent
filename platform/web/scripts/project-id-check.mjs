// 项目 ID 自动推导的单元回归（不需要浏览器、不需要后端）。
//
// 直接把 src/utils/projectId.ts 编成 ESM 再跑断言：测的是运行中的同一份代码，
// 不是抄一遍逻辑。
//
// 运行：node scripts/project-id-check.mjs
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✅' : '❌'} ${name}  ${detail ?? ''}`)
}

const tsc = join(process.cwd(), 'node_modules', 'typescript', 'bin', 'tsc')
if (!existsSync(tsc)) {
  console.error('找不到本地 tsc（先 npm install / pnpm install）')
  process.exit(1)
}

const outDir = mkdtempSync(join(tmpdir(), 'project-id-'))
let mod
try {
  execFileSync(process.execPath, [
    tsc, join('src', 'utils', 'projectId.ts'),
    '--outDir', outDir,
    '--module', 'esnext', '--target', 'es2022', '--moduleResolution', 'bundler'
  ], { stdio: 'inherit' })
  mod = await import(pathToFileURL(join(outDir, 'projectId.js')).href)
} finally {
  // 编译产物只在本次断言期间需要
  setTimeout(() => rmSync(outDir, { recursive: true, force: true }), 100)
}

const { idFromName, shouldApplySuggestion, applySelection } = mod

// 1. 名字 → ID
check('目录名转大写去符号', idFromName('untitled') === 'UNTITLED', idFromName('untitled'))
check('连字符/下划线被去掉', idFromName('Ruoyi-Vue_Plus') === 'RUOYIVUEPLUS', idFromName('Ruoyi-Vue_Plus'))
check('超长截断到 16 位', idFromName('a'.repeat(40)).length === 16, String(idFromName('a'.repeat(40)).length))
check('纯中文推不出 ID（返回空）', idFromName('我的项目') === '', JSON.stringify(idFromName('我的项目')))
check('数字开头原样返回（由调用方判非法）', idFromName('123abc') === '123ABC', idFromName('123abc'))

// 2. 覆盖策略
check('空输入 → 填入', shouldApplySuggestion('', '') === true)
check('空输入 + 有旧建议 → 填入', shouldApplySuggestion('', 'OLD') === true)
check('仍是上次自动值 → 跟随更新（bug 修复点）', shouldApplySuggestion('DOCUMENTS', 'DOCUMENTS') === true)
check('用户手改过 → 保留', shouldApplySuggestion('MYOWN', 'DOCUMENTS') === false)

// 3. 场景串联：就是用户报的那条
let state = { current: '', lastSuggested: '' }
state = applySelection(state, 'Documents')
check('第一次选「文档」→ DOCUMENTS', state.current === 'DOCUMENTS', JSON.stringify(state))
state = applySelection(state, 'Downloads')
check('再选「下载」→ ID 跟着变 DOWNLOADS', state.current === 'DOWNLOADS', JSON.stringify(state))
state = applySelection(state, 'Desktop')
check('第三次选「桌面」→ DESKTOP', state.current === 'DESKTOP', JSON.stringify(state))

// 4. 手改后不再被覆盖，清空后恢复自动
let manual = { current: 'MYOWN', lastSuggested: 'DOWNLOADS' }
manual = applySelection(manual, 'Desktop')
check('手改过 → 换目录不覆盖', manual.current === 'MYOWN', JSON.stringify(manual))
manual = applySelection({ current: '', lastSuggested: manual.lastSuggested }, 'Desktop')
check('清空后再选 → 恢复自动', manual.current === 'DESKTOP', JSON.stringify(manual))

// 5. 非法候选不动输入（避免把用户填的值清成空）
const kept = applySelection({ current: 'KEEP', lastSuggested: 'KEEP' }, '我的项目')
check('推不出合法 ID 时不动输入', kept.current === 'KEEP', JSON.stringify(kept))

const failed = results.filter((r) => !r.pass)
console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : `\n✅ 全部 ${results.length} 项通过`)
if (failed.length) process.exitCode = 1
