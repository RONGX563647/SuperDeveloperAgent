#!/usr/bin/env node
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const DOCS = join(ROOT, 'docs')
const ROOT_INDEX = join(DOCS, 'README.md')
const CODE_MAP = join(DOCS, 'CODE-MAP.md')
const SKIP_DIRS = new Set(['node_modules', '.git', 'target', 'dist'])

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (name.endsWith('.md')) out.push(full)
  }
  return out
}

function linksIn(file) {
  const text = readFileSync(file, 'utf8').replace(/```[\s\S]*?```/g, '')
  const links = []
  const re = /\[[^\]]*\]\(\s*<?([^)\s>]+)>?[^)]*\)/g
  let match
  while ((match = re.exec(text))) {
    const target = match[1]
    if (/^(https?:|mailto:|tel:|#|\/)/.test(target)) continue
    const path = target.split('#')[0]
    if (!path.endsWith('.md')) continue
    links.push(resolve(dirname(file), path))
  }
  return links
}

function docsRelative(file) {
  return relative(DOCS, file).split(sep).join('/')
}

/** 索引类与记录类文档不要求进 CODE-MAP；其余文档必须一一对应。 */
function requiresCodeMap(file) {
  const rel = docsRelative(file)
  if (file === ROOT_INDEX || file === CODE_MAP) return false
  if (rel.endsWith('README.md') || rel.endsWith('_template.md')) return false
  if (rel.startsWith('changelog/') || rel.startsWith('deprecated/')) return false
  if (rel.startsWith('_research/')) return false
  if (rel.startsWith('adr/') && /^000\d-/.test(rel.slice(4))) return false
  return true
}

function parseCodeMap(text) {
  const rows = []
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed.startsWith('|') || trimmed.includes('---')) continue
    const cells = trimmed.slice(1, -1).split('|').map((cell) => cell.trim())
    if (cells.length < 6 || cells[0] === '类别') continue
    const [category, docCell, status, anchorCell, lastVerified] = cells
    const link = /\]\(([^)]+)\)/.exec(docCell)
    const doc = link ? link[1] : docCell
    const anchors = [...anchorCell.matchAll(/`([^`]+)`/g)].map((m) => m[1])
    rows.push({ category, doc, status, anchors, lastVerified })
  }
  return rows
}

if (!existsSync(CODE_MAP)) {
  console.error('缺少 docs/CODE-MAP.md：无法校验文档与代码锚点。')
  process.exit(1)
}

const files = walk(DOCS)
const fileSet = new Set(files)
const hubs = files.filter((file) => file.endsWith('README.md'))
const registered = new Set()
for (const hub of hubs) {
  for (const target of linksIn(hub)) registered.add(target)
}

const unregistered = files.filter((file) => file !== ROOT_INDEX && !registered.has(file))
if (unregistered.length > 0) {
  console.error(`未登记文档 ${unregistered.length} 篇：`)
  for (const file of unregistered) console.error(`  - ${relative(ROOT, file)}`)
  console.error('\n把文档链接进 docs/README.md 或其所在目录的 README.md 后再提交。')
  process.exit(1)
}

const rows = parseCodeMap(readFileSync(CODE_MAP, 'utf8'))
const mapped = new Map()
const problems = []
let anchorCount = 0

for (const row of rows) {
  if (row.category === '类别') continue
  const docAbs = row.doc.startsWith('docs/')
    ? resolve(ROOT, row.doc)
    : resolve(DOCS, row.doc)
  if (!fileSet.has(docAbs)) {
    problems.push(`CODE-MAP 指向不存在的文档：${row.doc}`)
    continue
  }
  if (mapped.has(docAbs)) problems.push(`CODE-MAP 重复登记：${row.doc}`)
  mapped.set(docAbs, row)

  if (!/^\d{4}-\d{2}-\d{2}$/.test(row.lastVerified)) {
    problems.push(`最后核对日期格式错误：${row.doc} -> ${row.lastVerified || '空'}`)
  }

  if (row.anchors.length === 0) {
    if (!row.status.includes('规划')) {
      problems.push(`缺少代码锚点：${row.doc}（未实现请把状态标为“规划”）`)
    }
    continue
  }
  for (const anchor of row.anchors) {
    anchorCount++
    if (!existsSync(join(ROOT, anchor))) {
      problems.push(`代码锚点不存在：${row.doc} -> ${anchor}`)
    }
  }
}

const required = files.filter(requiresCodeMap)
const missing = required.filter((file) => !mapped.has(file))
for (const file of missing) {
  problems.push(`文档未进入 CODE-MAP：${relative(ROOT, file)}`)
}

if (problems.length > 0) {
  console.error(`文档-代码对照检查失败 ${problems.length} 项：`)
  for (const problem of problems) console.error(`  - ${problem}`)
  console.error('\n修正 docs/CODE-MAP.md：补文档、改锚点、或把未实现文档标为“规划”。')
  process.exit(1)
}

console.log(
  `OK：${files.length} 篇文档均已登记（${hubs.length} 个索引）；` +
    `CODE-MAP ${rows.length} 篇、${anchorCount} 个代码锚点均存在。`,
)
