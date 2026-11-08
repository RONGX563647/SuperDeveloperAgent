// 流水线工作台交互回归：守住"少填少选、错误可读"的形态。
// 运行：node scripts/pipeline-ux-check.mjs [projectId]
//
// 说明：/pipeline 支持 ?projectId= 直达（刷新/分享不掉项目），脚本按项目 ID 定位。
// 默认用 JAVADEEPER（没有 ci/pipeline.yml 的项目同样能跑完这些断言，顺带覆盖空态）。
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const ORIGIN = process.env.AGENT_WEB_ORIGIN ?? 'http://localhost:5173'
const PROJECT = process.argv[2] ?? process.env.AGENT_PROJECT ?? 'JAVADEEPER'
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
  const target = `${ORIGIN}/pipeline?projectId=${PROJECT}`
  await page.goto(target, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1200))
  if (page.url().includes('/login')) {
    await ensureLoggedIn(page)
    await page.goto(target, { waitUntil: 'networkidle2', timeout: 30000 })
  }
  // 等流水线/历史/动作/提交四类数据各完成一次请求
  await new Promise((r) => setTimeout(r, 2200))

  // 1. 触发形态：一个触发块；项目/环境由全局状态接管，只保留必要选择
  const trigger = await page.evaluate(() => {
    const bar = document.querySelector('.trigger')
    return {
      exists: !!bar,
      pipelineSelect: !!bar?.querySelector('.t-pipeline'),
      shaSelect: !!bar?.querySelector('.t-sha'),
      targetSelect: !!bar?.querySelector('.t-target'),
      runBtn: !!bar?.querySelector('button.el-button--primary'),
      selects: bar ? bar.querySelectorAll('.el-select').length : -1
    }
  })
  check('触发块存在', trigger.exists, '')
  check('流水线 / 提交 / 部署目标三项选择齐备',
    trigger.pipelineSelect && trigger.shaSelect && trigger.targetSelect, JSON.stringify(trigger))
  check('提交是「从 git 历史里选」，不是手敲输入框', trigger.shaSelect, '')
  check('触发块只有 3 个下拉（项目/环境由全局状态接管）', trigger.selects === 3, `selects=${trigger.selects}`)
  check('运行按钮存在', trigger.runBtn, '')

  // 2. 提交自动预填（不用手动复制 sha）；el-select 的已选项渲染在文本节点里，读整个控件文本
  const shaText = await page.evaluate(() =>
    document.querySelector('.t-sha')?.textContent?.trim() ?? '')
  check('提交已自动预填（减少复制粘贴）', /[0-9a-f]{7,40}/.test(shaText), `"${shaText.slice(0, 36)}"`)

  // 3. 历史条目：带一键重跑（常显但低调：hover 才上色）
  const hist = await page.evaluate(() => {
    const items = document.querySelectorAll('.hist-item')
    const first = items[0]
    const btn = first?.querySelector('.hist-rerun')
    return {
      count: items.length,
      rerun: !!btn,
      visible: btn ? getComputedStyle(btn).opacity !== '0' : null,
      box: btn ? Math.round(btn.getBoundingClientRect().width) : 0
    }
  })
  check('历史条目带一键重跑按钮', hist.rerun, `items=${hist.count}`)
  check('重跑按钮常显且点击目标够大', hist.visible === true && hist.box >= 20, `${hist.box}px`)

  // 4. 动作区：每个动作「查看命令 + 执行」两个明确操作，级别用可读文字
  const actions = await page.evaluate(() => {
    const rows = document.querySelectorAll('.action-row')
    const first = rows[0]
    return {
      rows: rows.length,
      toggle: !!first?.querySelector('.ar-toggle'),
      run: !!first?.querySelector('.ar-run'),
      tag: first?.querySelector('.ar-tag')?.textContent?.trim() ?? ''
    }
  })
  check('动作行存在', actions.rows > 0, `rows=${actions.rows}`)
  check('每个动作有「查看命令」与「执行」', actions.toggle && actions.run,
    `toggle=${actions.toggle} run=${actions.run}`)
  check('级别用「可直接执行 / 需确认」而非代号',
    actions.tag.includes('直接执行') || actions.tag.includes('需确认'), actions.tag)

  // 5. 布局：外层不滚，左右两栏（列表区 | 运行详情）
  const layout = await page.evaluate(() => {
    const doc = document.documentElement
    const left = document.querySelector('.wp-left')
    const right = document.querySelector('.wp-right')
    return {
      noScroll: doc.scrollHeight <= doc.clientHeight + 1,
      twoCols: !!left && !!right
        && left.getBoundingClientRect().left < right.getBoundingClientRect().left,
      leftW: Math.round(left?.getBoundingClientRect().width ?? 0),
      rightW: Math.round(right?.getBoundingClientRect().width ?? 0)
    }
  })
  check('外层无整页滚动', layout.noScroll, '')
  check('左右两栏布局（左列表 / 右详情）', layout.twoCols, `${layout.leftW}px | ${layout.rightW}px`)

  // 6. 空详情态是轻量引导（插图 + 一句话 + 一个动作），不是大块表格占位
  const empty = await page.evaluate(() => {
    const el = document.querySelector('.run-empty')
    return el
      ? {
          text: el.innerText.replace(/\s+/g, ' ').trim().slice(0, 40),
          tables: el.querySelectorAll('table').length,
          hasAction: !!el.querySelector('.re-btn')
        }
      : null
  })
  check('空详情态为轻量引导', empty !== null && empty.tables === 0 && empty.hasAction,
    empty ? `"${empty.text}"` : 'null')

  await page.screenshot({ path: process.argv[3] ?? 'pipeline-ux.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部交互断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
