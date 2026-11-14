#!/usr/bin/env node
// 文档链接巡检：扫描仓库内 Markdown 的相对链接，报告失效目标。
// 用法：node scripts/docs/check-links.mjs [--root .] [--json]
// 退出码：0 = 无断链；1 = 存在断链。外部参考仓库（../RuoYi*、../agentscope-java）按约定忽略。

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'

const args = process.argv.slice(2)
const rootArg = args.indexOf('--root')
const ROOT = resolve(rootArg >= 0 ? args[rootArg + 1] : '.')
const AS_JSON = args.includes('--json')

const SKIP_DIRS = new Set(['node_modules', '.git', 'target', 'dist', 'data', 'demo-workspaces'])
const IGNORED_PREFIXES = ['../RuoYi', '../agentscope-java']

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) walk(full, out)
    else if (name.endsWith('.md')) out.push(full)
  }
  return out
}

const broken = []
for (const file of walk(ROOT)) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split(/\r?\n/)
  lines.forEach((line, idx) => {
    const re = /\[[^\]]*\]\(([^)\s]+)\)/g
    let m
    while ((m = re.exec(line)) !== null) {
      const link = m[1]
      if (/^(https?:|mailto:|tel:|#)/.test(link)) continue
      if (IGNORED_PREFIXES.some((p) => link.startsWith(p))) continue
      const target = link.split('#')[0]
      if (!target) continue
      const abs = resolve(dirname(file), target)
      try {
        statSync(abs)
      } catch {
        broken.push({
          file: relative(ROOT, file).split(sep).join('/'),
          line: idx + 1,
          link,
        })
      }
    }
  })
}

if (AS_JSON) {
  console.log(JSON.stringify({ broken, total: broken.length }, null, 2))
} else if (broken.length === 0) {
  console.log('OK：未发现失效的内部文档链接。')
} else {
  console.error(`发现 ${broken.length} 条失效链接：`)
  for (const b of broken) console.error(`  ${b.file}:${b.line}  ->  ${b.link}`)
}

process.exit(broken.length === 0 ? 0 : 1)
