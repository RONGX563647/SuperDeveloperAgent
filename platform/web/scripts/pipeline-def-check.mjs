// 回归：仓库缺 ci/pipeline.yml 时，流水线页必须"说清楚下一步"，而不是跑一条失败历史。
//
// 覆盖的场景就是用户实际踩到的那个坑：
//   1. 白名单为空 → 触发条给出"去项目页生成脚本"的指引（不再列 release/verify 让用户点）；
//   2. 运行按钮 disabled（点了也不会有垃圾 run）；
//   3. 对旧的失败历史点「重跑」→ 顶部错误条显示可照做的原因（含 ci/pipeline.yml 与 生成脚本）。
//
// 运行：node scripts/pipeline-def-check.mjs [projectId]
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const ORIGIN = process.env.AGENT_WEB_ORIGIN ?? 'http://localhost:5173'
const PROJECT = process.argv[2] ?? 'JAVADEEPER'
const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'
]

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✅' : '❌'} ${name}  ${detail ?? ''}`)
}

const browser = await puppeteer.launch({
  executablePath: EDGE_CANDIDATES.find((p) => existsSync(p)),
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu', '--window-size=1440,900']
})

/** 登录（守卫后的页面都必须先有会话）。 */
async function ensureLoggedIn(page) {
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('.login-card input')
    if (!inputs.length) return
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], 'devops')
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], 'devops-2026')
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 2500))
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  await page.goto(`${ORIGIN}/pipeline?projectId=${PROJECT}`, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1200))
  if (page.url().includes('/login')) {
    await ensureLoggedIn(page)
    await page.goto(`${ORIGIN}/pipeline?projectId=${PROJECT}`, { waitUntil: 'networkidle2', timeout: 30000 })
    await new Promise((r) => setTimeout(r, 2000))
  }

  // 1. 缺定义 → 指引而非"未配置"
  const hint = await page.evaluate(() => document.querySelector('.trigger .t-hint')?.textContent?.trim() ?? '')
  check('缺定义时给出可照做的指引', hint.includes('ci/pipeline.yml') && hint.includes('生成流水线脚本'), hint)

  // 2. 运行按钮不可点（避免产生必然失败的 run）
  const runBtn = await page.evaluate(() => {
    const btn = document.querySelector('.trigger button.el-button--primary')
    return btn ? { disabled: btn.disabled || btn.classList.contains('is-disabled') } : null
  })
  check('运行按钮置灰（不会触发注定失败的 run）', runBtn?.disabled === true, JSON.stringify(runBtn))

  // 3. 对旧的失败历史点「重跑」→ 顶部错误条解释原因
  const hasHistory = await page.evaluate(() => !!document.querySelector('.hist-item'))
  if (!hasHistory) {
    check('历史里有可重跑条目（跳过错误条断言）', false, '该项目没有构建历史')
  } else {
    await page.hover('.hist-item')
    await new Promise((r) => setTimeout(r, 300))
    await page.evaluate(() => document.querySelector('.hist-item .hist-rerun')?.click())
    await new Promise((r) => setTimeout(r, 1800))
    const alert = await page.evaluate(() =>
      document.querySelector('.top-alert')?.textContent?.trim() ?? '')
    check('重跑失败时顶部错误条说明原因', alert.includes('ci/pipeline.yml'), alert)
    check('错误条指向下一步（生成脚本 / 提交）',
      alert.includes('生成流水线脚本') || alert.includes('未提交'), alert)
  }

  // 4. 外壳与布局没被挤坏
  const layout = await page.evaluate(() => {
    const doc = document.documentElement
    return {
      noScroll: doc.scrollHeight <= doc.clientHeight + 1,
      hasAside: !!document.querySelector('.aside'),
      triggerHeight: Math.round(document.querySelector('.trigger')?.getBoundingClientRect().height ?? 0)
    }
  })
  check('未破坏布局（外层不滚 / 侧栏在 / 触发条有高度）',
    layout.noScroll && layout.hasAside && layout.triggerHeight > 40, JSON.stringify(layout))

  await page.screenshot({ path: process.argv[3] ?? 'pipeline-def-check.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
