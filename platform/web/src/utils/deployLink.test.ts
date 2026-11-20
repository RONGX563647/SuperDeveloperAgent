/**
 * 「打开部署地址」URL 归一化的回归脚本（零依赖）。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/deployLink.test.ts
 */
import { asPageUrl } from './deployLink.ts'

let passed = 0
let failed = 0

function check(name: string, condition: boolean, detail = ''): void {
  if (condition) {
    passed++
    console.log(`  ✓ ${name}`)
  } else {
    failed++
    console.error(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

// 1. 历史 health 路径 → 归一根路径（bug 根因）
check('actuator/health 归一到根',
  asPageUrl('http://127.0.0.1:8184/actuator/health') === 'http://127.0.0.1:8184/')
check('healthz 归一到根',
  asPageUrl('http://127.0.0.1:8688/healthz') === 'http://127.0.0.1:8688/')
check('带查询串的 health 归一到根',
  asPageUrl('http://host:8080/actuator/health?x=1') === 'http://host:8080/')

// 2. 新构建的根路径保持
check('根路径保持',
  asPageUrl('http://127.0.0.1:8184/') === 'http://127.0.0.1:8184/')
check('深层前端路由也归一根（SPA 首页）',
  asPageUrl('http://host:8080/foo/bar') === 'http://host:8080/')

// 3. https / 域名 / 默认端口
check('https 保留',
  asPageUrl('https://example.com/actuator/health') === 'https://example.com/')
check('域名带端口',
  asPageUrl('http://deploy.example.com:9000/health') === 'http://deploy.example.com:9000/')

// 4. 非 http(s) 与空值 → 不可打开
check('非 http 协议拒绝', asPageUrl('ftp://host/x') === '')
check('空串拒绝', asPageUrl('') === '')
check('undefined 安全', asPageUrl(undefined) === '')
check('相对路径拒绝', asPageUrl('/actuator/health') === '')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
