// 「未登录看不到控制台」结构回归：登录页绝不允许出现侧栏/主区外壳元素。
// 运行：node scripts/shell-guard-check.mjs [base]
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const BASE = process.argv[2] ?? 'http://localhost:5173'
const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'
]

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✅' : '❌'} ${name}  ${detail}`)
}

const browser = await puppeteer.launch({
  executablePath: EDGE_CANDIDATES.find((p) => existsSync(p)),
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu', '--window-size=1440,900']
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })

  // 1. 未登录（无 token）直接访问 /login：DOM 里绝不允许出现外壳元素
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1200))

  const loginDom = await page.evaluate(() => ({
    path: location.pathname,
    hasShell: !!document.querySelector('.app-shell'),
    hasAside: !!document.querySelector('.aside'),
    hasMain: !!document.querySelector('.app-main'),
    hasLoginForm: !!document.querySelector('.login-card'),
    navEntries: document.querySelectorAll('.nav-entry').length
  }))
  check('未登录访问 /login 不渲染外壳（.app-shell）', !loginDom.hasShell, `hasShell=${loginDom.hasShell}`)
  check('未登录看不到侧栏（.aside）', !loginDom.hasAside, `hasAside=${loginDom.hasAside}`)
  check('未登录看不到主区（.app-main）', !loginDom.hasMain, `hasMain=${loginDom.hasMain}`)
  check('登录表单正常渲染', loginDom.hasLoginForm, '')
  check('侧栏导航入口不在 DOM', loginDom.navEntries === 0, `count=${loginDom.navEntries}`)

  // 2. 未登录直接访问受保护路由 /pipeline：被守卫弹到 /login，且同样无外壳
  await page.goto(BASE + '/pipeline', { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1500))
  const guarded = await page.evaluate(() => ({
    path: location.pathname,
    hasShell: !!document.querySelector('.app-shell'),
    hasAside: !!document.querySelector('.aside')
  }))
  check('未登录访问 /pipeline 被守卫弹回登录', guarded.path === '/login', `path=${guarded.path}`)
  check('弹回后的登录页同样无外壳', !guarded.hasShell && !guarded.hasAside,
    `shell=${guarded.hasShell} aside=${guarded.hasAside}`)

  // 3. 登录后外壳恢复：侧栏 + 主区都出现
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('.login-card input')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], 'devops'); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], 'devops-2026'); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 2500))
  const authed = await page.evaluate(() => ({
    path: location.pathname,
    hasShell: !!document.querySelector('.app-shell'),
    hasAside: !!document.querySelector('.aside'),
    userName: document.querySelector('.user-name')?.textContent?.trim() ?? ''
  }))
  check('登录后外壳出现（.app-shell + .aside）', authed.hasShell && authed.hasAside,
    `shell=${authed.hasShell} aside=${authed.hasAside}`)
  check('登录后侧栏显示身份', authed.userName.includes('运维开发'), `name="${authed.userName}"`)

  // 4. 登出 → 回登录页且外壳消失
  await page.evaluate(() => {
    const btn = document.querySelector('.logout-btn')
    if (btn) btn.click()
  })
  await new Promise((r) => setTimeout(r, 1500))
  const loggedOut = await page.evaluate(() => ({
    path: location.pathname,
    hasShell: !!document.querySelector('.app-shell'),
    hasAside: !!document.querySelector('.aside'),
    token: localStorage.getItem('agent.token') ?? ''
  }))
  check('登出后回登录页', loggedOut.path === '/login', `path=${loggedOut.path}`)
  check('登出后外壳消失（不再挂着侧栏）', !loggedOut.hasShell && !loggedOut.hasAside,
    `shell=${loggedOut.hasShell}`)
  check('登出后 token 已清除', loggedOut.token === '', '')

  await page.screenshot({ path: process.argv[3] ?? 'shell-guard.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 外壳隔离全部断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
