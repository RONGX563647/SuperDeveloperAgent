// 登录页交互回归：渲染、错误提示、成功跳转、守卫、无效 token。
// el-input 的值要用原生 setter + input 事件设置（triple-click 选不中组件值）。
// 运行：node scripts/login-check.mjs [base]
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

/** 以 Vue 认可的方式设置输入值（原生 setter + input 事件）。 */
async function fillInputs(page, user, pwd) {
  await page.evaluate((u, p) => {
    const inputs = document.querySelectorAll('.login-card input')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], u); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], p); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  }, user, pwd)
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  const consoleErrors = []
  page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message))

  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1200))

  // 1. 表单渲染 + 用户名不预填 + 空态按钮禁用
  const form = await page.evaluate(() => {
    const inputs = [...document.querySelectorAll('.login-card input')]
    const btn = document.querySelector('.login-card button.el-button--primary')
    return {
      count: inputs.length,
      userValue: inputs[0]?.value ?? '',
      btnDisabled: btn?.disabled,
      pwdType: inputs[1]?.type
    }
  })
  check('表单渲染（2 输入框 + 密码型）', form.count === 2 && form.pwdType === 'password',
    `count=${form.count}`)
  check('用户名不预填（避免拼接出错的安全习惯）', form.userValue === '', `value="${form.userValue}"`)
  check('空表单时按钮禁用', form.btnDisabled === true, `disabled=${form.btnDisabled}`)

  // 2. 错密码：显示错误且不跳转
  await fillInputs(page, 'devops', 'wrong-password')
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 1500))
  const afterWrong = await page.evaluate(() => ({
    url: location.pathname,
    alert: document.querySelector('.login-card .el-alert')?.textContent?.trim() ?? ''
  }))
  check('错密码显示可读错误', afterWrong.alert.includes('用户名或密码错误'),
    `"${afterWrong.alert.slice(0, 30)}"`)
  check('错密码不跳转', afterWrong.url === '/login', `path=${afterWrong.url}`)

  // 3. 正确密码：跳转 + token + 侧栏身份
  await fillInputs(page, 'devops', 'devops-2026')
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 2500))
  const afterOk = await page.evaluate(() => ({
    path: location.pathname,
    token: (localStorage.getItem('agent.token') ?? '').length > 10,
    userName: document.querySelector('.user-name')?.textContent?.trim() ?? ''
  }))
  check('登录成功跳转主界面', afterOk.path === '/chat', `path=${afterOk.path}`)
  check('token 已写入本地', afterOk.token, '')
  check('侧栏显示真实身份', afterOk.userName.includes('运维开发'), `name="${afterOk.userName}"`)

  // 4. 刷新后守卫放行
  await page.reload({ waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1500))
  const afterReload = await page.evaluate(() => location.pathname)
  check('刷新后仍在主界面（守卫校验通过）', afterReload !== '/login', `path=${afterReload}`)

  // 5. 无效 token → 拦截并清除
  await page.evaluate(() => localStorage.setItem('agent.token', 'invalid-token-xxx'))
  await page.goto(BASE + '/pipeline', { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1500))
  const withBad = await page.evaluate(() => ({
    path: location.pathname,
    token: localStorage.getItem('agent.token') ?? ''
  }))
  check('无效 token 被拦截并清除', withBad.path === '/login' && withBad.token === '',
    `path=${withBad.path}`)

  check('无 JS 运行时错误', consoleErrors.length === 0,
    consoleErrors.slice(0, 2).join(' | ') || 'clean')

  await page.screenshot({ path: process.argv[3] ?? 'login-check.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部登录断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
