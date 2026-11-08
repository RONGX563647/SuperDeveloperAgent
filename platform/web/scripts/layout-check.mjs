// 布局回归检查：用真实浏览器测量，而不是只看 DOM 结构。
// 受守卫保护的页面需要先登录——脚本检测到 /login 会自动走一遍登录。
// 运行：node scripts/layout-check.mjs [url] [screenshot]
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const TARGET_URL = process.argv[2] ?? 'http://localhost:5173/chat'
const ORIGIN = new URL(TARGET_URL).origin
const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'
]

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
}

const browser = await puppeteer.launch({
  executablePath: EDGE_CANDIDATES.find((p) => existsSync(p)),
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu', '--window-size=1440,900']
})

/** 登录（守卫后的页面测量前置步骤）。 */
async function ensureLoggedIn(page) {
  await page.goto(ORIGIN + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('.login-card input')
    if (!inputs.length) return
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(inputs[0], 'devops'); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    setter.call(inputs[1], 'devops-2026'); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.click('.login-card button.el-button--primary')
  await new Promise((r) => setTimeout(r, 2500))
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  await page.goto(TARGET_URL, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1200))

  // 被守卫弹到登录页 → 登录后重试一次
  if (page.url().includes('/login')) {
    await ensureLoggedIn(page)
    await page.goto(TARGET_URL, { waitUntil: 'networkidle2', timeout: 30000 })
    await new Promise((r) => setTimeout(r, 1200))
  }

  const m = await page.evaluate(() => {
    const rect = (sel) => {
      const el = document.querySelector(sel)
      if (!el) return null
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      return {
        w: Math.round(r.width),
        h: Math.round(r.height),
        top: Math.round(r.top),
        bottom: Math.round(r.bottom),
        overflowY: cs.overflowY,
        scrollH: el.scrollHeight,
        clientH: el.clientHeight
      }
    }
    const doc = document.documentElement
    const body = document.body
    return {
      viewport: { w: window.innerWidth, h: window.innerHeight },
      docScroll: { scrollH: doc.scrollHeight, clientH: doc.clientHeight, scrollW: doc.scrollWidth },
      bodyScroll: { scrollH: body.scrollHeight, clientH: body.clientHeight },
      shell: rect('.app-shell'),
      aside: rect('.aside'),
      main: rect('.app-main'),
      header: rect('.page-header'),
      list: rect('.chat-list'),
      composer: rect('.composer-wrap'),
      composerBox: rect('.composer'),
      pageScroll: rect('.page-scroll')
    }
  })

  const vp = m.viewport
  check('外层无纵向滚动（doc.scrollHeight <= clientHeight）',
    m.docScroll.scrollH <= m.docScroll.clientH + 1,
    `doc ${m.docScroll.scrollH}/${m.docScroll.clientH}`)
  check('外层无横向滚动（doc.scrollWidth <= clientWidth）',
    m.docScroll.scrollW <= vp.w + 1, `scrollW ${m.docScroll.scrollW} / vp ${vp.w}`)
  check('app-shell 撑满视口', m.shell && Math.abs(m.shell.h - vp.h) <= 1,
    `shell ${m.shell?.h} vs vp ${vp.h}`)
  check('侧栏宽度 ≈ 260px', m.aside && Math.abs(m.aside.w - 260) <= 2, `aside ${m.aside?.w}`)
  check('侧栏通高（与 shell 等高）', m.aside && Math.abs(m.aside.h - vp.h) <= 1,
    `aside ${m.aside?.h}`)
  check('主区在侧栏右侧', m.main && m.aside && m.main.w > 0 && m.aside.w + m.main.w <= vp.w + 2,
    `aside ${m.aside?.w} + main ${m.main?.w} <= ${vp.w}`)
  // DSH 形态把顶栏从 60px 收到 52px（对齐 --header-height），断言跟随新基准
  check('主区 Header 高度 ≈ 52px', m.header && Math.abs(m.header.h - 52) <= 1, `header ${m.header?.h}`)
  check('Header 在主区顶部', m.header && m.header.top <= 1, `top ${m.header?.top}`)

  if (m.list) {
    check('聊天区可独立滚动', m.list.overflowY === 'auto' && m.list.scrollH >= m.list.clientH,
      `overflowY=${m.list.overflowY} scroll ${m.list.scrollH}/${m.list.clientH}`)
  }
  if (m.composer) {
    check('输入区完整在视口内（不被遮挡）', m.composer.bottom <= vp.h + 1,
      `composer bottom ${m.composer.bottom} / vp ${vp.h}`)
    check('输入框宽度受内容列约束（<= 56rem+padding）', m.composerBox && m.composerBox.w <= 940,
      `composer ${m.composerBox?.w}`)
  }
  if (m.pageScroll) {
    check('页面内容区可独立滚动', m.pageScroll.overflowY === 'auto',
      `overflowY=${m.pageScroll.overflowY}`)
    check('页面滚动区完整在视口内', m.pageScroll.bottom <= vp.h + 1,
      `bottom ${m.pageScroll.bottom} / vp ${vp.h}`)
  }

  await page.screenshot({ path: process.argv[3] ?? 'layout-chat.png' })
  console.log(JSON.stringify({ url: URL, metrics: m, checks: results }, null, 2))
  const failed = results.filter((r) => !r.pass)
  if (failed.length) {
    console.error(`\n❌ ${failed.length} 项不符合规范`)
    process.exitCode = 1
  } else {
    console.log('\n✅ 全部布局断言通过')
  }
} finally {
  await browser.close()
}
