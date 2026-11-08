// 流水线工作台交互简化回归：验证新交互形态的关键断言。
// 运行：node scripts/pipeline-ux-check.mjs
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'

const URL = process.argv[2] ?? 'http://127.0.0.1:5173/pipeline'
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
  await new Promise((r) => setTimeout(r, 1400))

  // 1. 触发形态：单行触发条（项目/环境已从全局 store 来，不再出现下拉；流水线下拉保留）
  const trigger = await page.evaluate(() => {
    const bar = document.querySelector('.trigger-bar')
    const selectLabels = bar
      ? [...bar.querySelectorAll('.el-select')].map((s) => s.closest('.t-pipeline') ? 'pipeline' : 'other')
      : []
    return {
      exists: !!bar,
      pipelineSelects: selectLabels.filter((l) => l === 'pipeline').length,
      otherSelects: selectLabels.filter((l) => l === 'other').length,
      shaInput: !!bar?.querySelector('.t-sha input'),
      runBtn: !!bar?.querySelector('button.el-button--primary'),
      quick: bar?.querySelector('.t-quick')?.textContent?.trim() ?? ''
    }
  })
  check('触发条存在且为单行形态', trigger.exists, '')
  check('项目/环境已由全局状态接管（触发条无这两个下拉）', trigger.otherSelects === 0,
    `otherSelects=${trigger.otherSelects}（流水线下拉 ${trigger.pipelineSelects} 个为预期保留）`)
  check('sha 输入框 + 运行按钮齐备', trigger.shaInput && trigger.runBtn, '')
  check('提供「上次成功」快捷 sha', trigger.quick.includes('上次成功'), trigger.quick)

  // 2. 自动预填：历史为空时 sha 由用户填；有历史时应预填
  const shaVal = await page.evaluate(() => document.querySelector('.t-sha input')?.value ?? '')
  check('sha 自动预填（减少复制粘贴）', shaVal.length >= 7, `value="${shaVal}"`)

  // 3. 历史条目：带一键重跑（hover 才显形）
  const hist = await page.evaluate(() => {
    const items = document.querySelectorAll('.history-item')
    const first = items[0]
    return {
      count: items.length,
      rerun: !!first?.querySelector('.hist-rerun'),
      rerunHidden: first ? getComputedStyle(first.querySelector('.hist-rerun')).opacity === '0' : null
    }
  })
  check('历史条目带一键重跑按钮', hist.rerun, `items=${hist.count}`)
  check('重跑按钮 hover 才显形', hist.rerunHidden === true, `opacity=0 → ${hist.rerunHidden}`)

  // 4. 动作收敛：每个动作只有一个「操作」下拉，不再三按钮平铺
  const actions = await page.evaluate(() => {
    const rows = document.querySelectorAll('.action-row')
    const first = rows[0]
    return {
      rows: rows.length,
      dropdowns: first ? first.querySelectorAll('.el-dropdown').length : -1,
      flatButtons: first ? [...first.querySelectorAll('.action-ops button.el-button--primary')].length : -1,
      tags: first ? [...first.querySelectorAll('.el-tag')].map((t) => t.textContent.trim()) : []
    }
  })
  check('动作行存在', actions.rows > 0, `rows=${actions.rows}`)
  check('动作操作收敛为单个下拉', actions.dropdowns === 1, `dropdowns=${actions.dropdowns}`)
  check('不再平铺主按钮', actions.flatButtons === 0, `flat=${actions.flatButtons}`)
  check('级别用「可直接执行/需确认」而非代号', actions.tags.some((t) => t === '可直接执行' || t === '需确认'), actions.tags.join('|'))

  // 5. 布局回归：外层不滚、grid 双栏
  const layout = await page.evaluate(() => {
    const doc = document.documentElement
    const grid = document.querySelector('.grid')
    const cols = grid ? getComputedStyle(grid).gridTemplateColumns.split(' ').length : -1
    return { noScroll: doc.scrollHeight <= doc.clientHeight + 1, cols }
  })
  check('外层无整页滚动', layout.noScroll, '')
  check('双栏布局（历史/动作 | 详情）', layout.cols === 2, `cols=${layout.cols}`)

  // 6. 空详情态为轻量引导（不是一大块占位）
  const empty = await page.evaluate(() => {
    const el = document.querySelector('.empty-state')
    return el ? { h: Math.round(el.getBoundingClientRect().height) } : null
  })
  check('空详情态为轻量引导', empty && empty.h < 200, `h=${empty?.h}`)

  await page.screenshot({ path: process.argv[3] ?? 'pipeline-ux.png' })
  const failed = results.filter((r) => !r.pass)
  console.log(failed.length ? `\n❌ ${failed.length} 项未通过` : '\n✅ 全部交互断言通过')
  if (failed.length) process.exitCode = 1
} finally {
  await browser.close()
}
