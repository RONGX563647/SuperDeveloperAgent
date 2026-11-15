#!/usr/bin/env node
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const DOCS = join(ROOT, 'docs')
const ROOT_INDEX = join(DOCS, 'README.md')
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

const files = walk(DOCS)
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

console.log(`OK：${files.length} 篇文档均已登记（${hubs.length} 个索引）。`)
