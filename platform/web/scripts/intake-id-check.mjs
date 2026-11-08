// 回归：接入向导里"重新选目录后项目 ID 必须跟着变"（曾经选第二个目录后 ID 还是上一个）。
//
// 覆盖三件事：
//   1. 第一次选目录 → ID 自动生成；
//   2. 再选另一个目录 → ID 跟着变（本次修复的 bug）；
//   3. 用户手改过 ID → 再选目录不覆盖（尊重人工输入）。
//
// 运行：node scripts/intake-id-check.mjs
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const ORIGIN = process.env.AGENT_WEB_ORIGIN ?? 'http://localhost:5173'
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await puppeteer.launch({
  executablePath: EDGE_CANDIDATES.find((p) => existsSync(p)),
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu', '--window-size=1440,900']
})

async function ensureLoggedIn(page) {
  await page.goto(new globalThis.URL('/login', ORIGIN).href, { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('.login-card input')
    if (!inputs.length) return
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], 'devops'); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], 'devops-2026'); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.click('.login-card button.el-button--primary')
  await sleep(2500)
}

/** 读「项目 ID」输入框当前值。 */
function readId(page) {
  return page.evaluate(() => {
    const label = [...document.querySelectorAll('.form-card .field')]
      .find((f) => f.querySelector('.field-label')?.textContent?.trim() === '项目 ID')
    return label?.querySelector('input')?.value ?? ''
  })
}

/** 直接给「项目 ID」输入框赋值（模拟人工输入）。 */
function typeId(page, value) {
  return page.evaluate((v) => {
    const label = [...document.querySelectorAll('.form-card .field')]
      .find((f) => f.querySelector('.field-label')?.textContent?.trim() === '项目 ID')
    const input = label?.querySelector('input')
    if (!input) return
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(input, v)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }, value)
}

/**
 * 打开目录选择器 → 点左侧快捷位置 → 「使用此目录」。
 *
 * <p>注意：这个对话框里的按钮一律用 `element.click()` 派发，不用 puppeteer 的鼠标点击——
 * headless 下 el-dialog 的过渡层会吃掉真实鼠标事件（真人在浏览器里点击是正常的）。
 */
async function pickShortcut(page, label, expectName) {
  await page.waitForSelector('.form-card .dir-pick', { timeout: 20000 })
  await page.evaluate(() => document.querySelector('.form-card .dir-pick')?.click())
  await page.waitForSelector('.dir-picker .picker-shell', { timeout: 20000 })
  await page.waitForFunction(
    () => document.querySelectorAll('.dir-picker .side-item').length > 0, { timeout: 20000 })

  const clicked = await page.evaluate((text) => {
    const btn = [...document.querySelectorAll('.dir-picker .side-item')]
      .find((b) => b.querySelector('.side-label')?.textContent?.trim() === text)
    if (!btn) return false
    btn.click()
    return true
  }, label)
  if (!clicked) throw new Error(`选择器里没有快捷位置：${label}`)

  // 等列表切到目标目录（footer 里显示完整路径）
  await page.waitForFunction((n) => {
    const t = document.querySelector('.dir-picker .footer .picked')?.textContent ?? ''
    return t.includes(n)
  }, { timeout: 15000 }, expectName)

  const pickedPath = await page.evaluate(() =>
    document.querySelector('.dir-picker .footer .picked')?.textContent?.trim() ?? '')
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.dir-picker .footer-actions button')]
      .find((b) => b.textContent.trim() === '使用此目录')
    btn?.click()
  })
  // 注意：el-dialog 关闭后 DOM 仍在（Element Plus 用 display 切换，不是销毁），
  // 所以判定"关掉"必须看可见性，不能看节点是否存在。
  await page.waitForFunction(() => {
    const dlg = document.querySelector('.dir-picker')
    if (!dlg) return true
    const box = dlg.closest('.el-overlay') ?? dlg
    const rect = dlg.getBoundingClientRect()
    return getComputedStyle(box).display === 'none' || rect.width === 0 || rect.height === 0
  }, { timeout: 20000 })
  await sleep(300)
  return pickedPath
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  const target = `${ORIGIN}/projects/intake`
  await page.goto(target, { waitUntil: 'networkidle2', timeout: 30000 })
  await sleep(1000)
  if (page.url().includes('/login')) {
    await ensureLoggedIn(page)
    await page.goto(target, { waitUntil: 'networkidle2', timeout: 30000 })
    await sleep(1500)
  }

  // 1. 第一次选目录
  const first = await pickShortcut(page, '文档', 'Documents')
  const idAfterFirst = await readId(page)
  check('首次选目录后自动生成项目 ID', /^[A-Z][A-Z0-9_]{1,15}$/.test(idAfterFirst),
    `${first} → ${idAfterFirst}`)
  check('ID 来自目录名', idAfterFirst === 'DOCUMENTS', `id=${idAfterFirst}`)

  // 2. 重新选另一个目录 → ID 必须跟着变（修复点）
  const second = await pickShortcut(page, '下载', 'Downloads')
  const idAfterSecond = await readId(page)
  check('换目录后项目 ID 跟着变', idAfterSecond === 'DOWNLOADS',
    `${first} → ${idAfterFirst}，${second} → ${idAfterSecond}`)
  check('目录显示已切到新选择', second.length > 0 && second !== first, `${first} vs ${second}`)

  // 3. 手改过 ID 后，再选目录不覆盖
  await typeId(page, 'MYOWN')
  await pickShortcut(page, '桌面', 'Desktop')
  const idAfterThird = await readId(page)
  check('手改过的 ID 不被自动覆盖', idAfterThird === 'MYOWN', `id=${idAfterThird}`)

  await page.screenshot({ path: process.argv[2] ?? 'intake-id.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
