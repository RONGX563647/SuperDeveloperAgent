// 设置 → 工具链镜像面板的真实渲染校验（登录后打真实接口，不是 mock）。
// 运行：先起后端（8090）与前端 dev server，然后 node scripts/sandbox-images-check.mjs [base] [shot]
//
// 为什么要这个脚本：面板的展示逻辑有单测，但"接口字段 → 表格单元格"这一段只有真浏览器能证明
// （el-table 的取值、tag 类型、空态/告警分支都可能在模板里写错而单测照样绿）。
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import puppeteer from 'puppeteer-core'

const BASE = process.argv[2] ?? 'http://localhost:5173'
const SHOT = process.argv[3] ?? ''
// 种子账号的默认初始密码（见 UserStore.DEFAULT_INITIAL_PASSWORD）；本机改过密码的用环境变量覆盖
const USER = process.env.CHECK_USER ?? 'devops'
const PASS = process.env.CHECK_PASS ?? 'admin-2026'
const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'
]

const results = []
function check(name, pass, detail = '') {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✅' : '❌'} ${name}  ${detail}`)
}

const browser = await puppeteer.launch({
  executablePath: EDGE_CANDIDATES.find((p) => existsSync(p)),
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu', '--window-size=1440,900']
})

async function login(page) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 800))
  // el-input 的值要用原生 setter + input 事件设置
  await page.evaluate((user, pass) => {
    const inputs = document.querySelectorAll('.login-card input')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], user); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], pass); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  }, USER, PASS)
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 2500))
  return page.evaluate(() => ({
    path: location.pathname,
    alert: document.querySelector('.login-card .el-alert')?.textContent?.trim() ?? ''
  }))
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 1000 })
  const pageErrors = []
  page.on('pageerror', (err) => pageErrors.push(err.message))

  const afterLogin = await login(page)
  check('登录成功', afterLogin.path !== '/login', `path=${afterLogin.path} ${afterLogin.alert}`)

  // 走深链，顺便验证 ?section= 能定位到面板（导航里不点也能到）
  await page.goto(BASE + '/settings?section=sandboxImages', { waitUntil: 'networkidle2' })
  await page.waitForSelector('.sandbox-image-panel', { timeout: 15000 })
  await new Promise((r) => setTimeout(r, 1200))

  const snap = await page.evaluate(() => {
    const panel = document.querySelector('.sandbox-image-panel')
    const rows = [...document.querySelectorAll('.image-table .el-table__body tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.innerText.trim()))
    return {
      heading: panel?.querySelector('h2')?.textContent?.trim() ?? '',
      alert: panel?.querySelector('.warn-alert')?.innerText?.replace(/\s+/g, ' ').trim() ?? '',
      summary: panel?.querySelector('.summary')?.innerText?.replace(/\s+/g, ' ').trim() ?? '',
      headers: [...document.querySelectorAll('.image-table .el-table__header th')]
        .map((th) => th.innerText.trim()).filter(Boolean),
      rows,
      tagTypes: [...document.querySelectorAll('.image-table .el-tag')]
        .map((t) => (t.className.match(/el-tag--(\w+)/)?.[1] ?? ''))
    }
  })

  check('面板标题', snap.heading === '工具链镜像', `"${snap.heading}"`)
  check('表格按 host+image 列出 3 条', snap.rows.length === 3, `rows=${snap.rows.length}`)
  check('列包含状态/下次重试/最后错误',
    ['主机', '镜像', '状态', '来源', '尝试', '下次重试', '最后错误']
      .every((h) => snap.headers.includes(h)),
    snap.headers.join('|'))
  check('失败行有告警', snap.alert.includes('1 个镜像未就绪'), `"${snap.alert.slice(0, 40)}"`)
  check('汇总显示就绪/失败计数',
    snap.summary.includes('已就绪 2') && snap.summary.includes('失败 1'), `"${snap.summary}"`)

  const failedRow = snap.rows.find((cells) => cells[1] === 'node:22-bookworm')
  const readyRow = snap.rows.find((cells) => cells[1] === 'maven:3.9-eclipse-temurin-21')
  check('失败行渲染状态与错误原文',
    Boolean(failedRow) && failedRow[2] === '失败'
      && failedRow[6].includes('E-302') && failedRow[4] === '3',
    failedRow ? failedRow.join(' | ') : 'missing')
  check('失败行显示退避倒计时', Boolean(failedRow) && /约 \d+ 分钟后重试/.test(failedRow[5]),
    failedRow ? failedRow[5] : 'missing')
  check('就绪行显示来源与已就绪',
    Boolean(readyRow) && readyRow[2] === '已就绪' && readyRow[4] === '1'
      && readyRow[3].length > 0,
    readyRow ? readyRow.join(' | ') : 'missing')
  check('tag 类型正确（success/danger 都在）',
    snap.tagTypes.includes('success') && snap.tagTypes.includes('danger'), snap.tagTypes.join(','))
  check('无 JS 运行时错误', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | ') || 'clean')

  if (SHOT) {
    mkdirSync(path.dirname(SHOT), { recursive: true })
    await page.screenshot({ path: SHOT, fullPage: true })
    console.log(`📷 ${SHOT}`)
  }

  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部面板断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
