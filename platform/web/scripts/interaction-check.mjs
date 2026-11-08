// 交互细节回归：粘底滚动、IME 组合、复制按钮、时间戳、光标、禁用附件。
// 运行：node scripts/interaction-check.mjs
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const URL = process.argv[2] ?? 'http://127.0.0.1:5173/chat'
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
  await page.goto(URL, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1200))

  // 1. 时间戳与复制按钮存在（气泡元信息在 DOM 里，hover 才显形）
  const meta = await page.evaluate(() => {
    const groups = document.querySelectorAll('.bubble-group')
    const first = groups[0]
    return {
      groups: groups.length,
      hasTime: !!first?.querySelector('.meta-time'),
      hasCopy: !!first?.querySelector('.meta-copy'),
      metaHidden: first ? getComputedStyle(first.querySelector('.bubble-meta')).opacity === '0' : null
    }
  })
  check('气泡带时间戳与复制按钮', meta.hasTime && meta.hasCopy, `groups=${meta.groups}`)
  check('气泡元信息默认隐藏（hover 显形）', meta.metaHidden === true, `opacity=0 → ${meta.metaHidden}`)

  // 2. 附件按钮应禁用（占位）
  const attach = await page.evaluate(() => {
    const btn = document.querySelector('.composer .icon-btn')
    return btn ? { disabled: btn.disabled, title: btn.title } : null
  })
  check('附件按钮为禁用占位（不误导用户）', attach?.disabled === true, `title="${attach?.title}"`)

  // 3. 粘底滚动：直接注入 DOM 不会滚底（绕过了 push 路径，属预期）；
  //    真实路径在下方通过 send() 验证。
  await page.evaluate(() => {
    const col = document.querySelector('.chat-column')
    for (let i = 0; i < 40; i++) {
      const row = document.createElement('div')
      row.className = 'row assistant'
      const b = document.createElement('div')
      b.className = 'bubble-group'
      b.innerHTML = `<div class="bubble"><span class="bubble-text">填充消息 ${i} —— 用于把列表撑长。</span></div><div class="bubble-meta"><span class="mono meta-time">10:00</span><button class="meta-copy">c</button></div>`
      row.appendChild(b)
      col.appendChild(row)
    }
  })
  await new Promise((r) => setTimeout(r, 200))
  const injectedNoAutoscroll = await page.evaluate(() => {
    const el = document.querySelector('.chat-list')
    return el.scrollHeight - el.scrollTop - el.clientHeight > 40
  })
  check('DOM 直插不触发滚动（绕过 push 属预期）', injectedNoAutoscroll, 'scrollTop 不变')

  // 滚回底部，恢复粘底状态
  await page.evaluate(() => {
    const el = document.querySelector('.chat-list')
    el.scrollTop = el.scrollHeight
    el.dispatchEvent(new Event('scroll'))
  })
  await new Promise((r) => setTimeout(r, 200))

  // 4. IME 组合：compositionstart 后 Enter 不应触发 send（消息数不变）
  const beforeIme = await page.evaluate(() => document.querySelectorAll('.row.user').length)
  await page.evaluate(() => {
    const ta = document.querySelector('.composer-input textarea')
    ta.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
  })
  await new Promise((r) => setTimeout(r, 300))
  const afterIme = await page.evaluate(() => document.querySelectorAll('.row.user').length)
  check('IME 组合期间 Enter 不发送', beforeIme === afterIme, `user msgs ${beforeIme} → ${afterIme}`)
  await page.evaluate(() => {
    const ta = document.querySelector('.composer-input textarea')
    ta.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
  })

  // 5. 真实发送路径：push → 光标出现 → 结束后粘底在最新消息。
  //    后端用 dummy key，provider 会 401，但这不影响前端链路（user 气泡 + error 气泡）。
  const beforeSend = await page.evaluate(() => document.querySelectorAll('.row').length)
  await page.type('.composer-input textarea', '布局验证消息')
  await page.keyboard.press('Enter')

  // 流式期间应出现光标（轮询最多 2s）
  let caretSeen = false
  for (let i = 0; i < 20; i++) {
    caretSeen = await page.evaluate(() => !!document.querySelector('.bubble .caret'))
    if (caretSeen) break
    await new Promise((r) => setTimeout(r, 100))
  }
  check('流式期间渲染光标', caretSeen, 'caret 可见')

  // 等流结束（error 气泡出现即终态）
  let settled = false
  for (let i = 0; i < 100; i++) {
    settled = await page.evaluate(() => !!document.querySelector('.bubble.error'))
    if (settled) break
    await new Promise((r) => setTimeout(r, 100))
  }
  check('发送后进入终态（错误也被呈现）', settled, 'error 气泡出现')

  await new Promise((r) => setTimeout(r, 400))
  const stuckBottom = await page.evaluate(() => {
    const el = document.querySelector('.chat-list')
    return el.scrollHeight - el.scrollTop - el.clientHeight < 40
  })
  const grew = await page.evaluate(() => document.querySelectorAll('.row').length)
  check('真实 push 后粘底到最新消息', stuckBottom, `rows ${beforeSend} → ${grew}`)

  // 6. 布局断言不回归（滚动区唯一性）
  const layout = await page.evaluate(() => {
    const doc = document.documentElement
    const list = document.querySelector('.chat-list')
    const cs = getComputedStyle(list)
    return {
      noPageScroll: doc.scrollHeight <= doc.clientHeight + 1,
      listScrolls: cs.overflowY === 'auto'
    }
  })
  check('外层仍无整页滚动', layout.noPageScroll, 'doc 不滚')
  check('消息区仍是唯一滚动容器', layout.listScrolls, 'overflowY=auto')

  await page.screenshot({ path: process.argv[3] ?? 'interaction-chat.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部交互断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
