/**
 * 「复用产物部署」入口的可见性规则（零依赖回归脚本）。
 *
 * 背景（BUG-12）：同样的代码要发到另一个环境、或只是想重发一次，重新构建要几分钟
 * 且会再产一次镜像；这次运行其实已经成功、产物就在本机。
 * 但入口不能无条件出现——未成功的运行没有可复用产物，给它按钮只会点出一个错误。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/reuseBuild.test.ts
 */
import { canReuseBuild, pickReusableImage } from './reuseBuild.ts'

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

// 1. 只有成功运行才展示入口
check('SUCCEEDED 可复用', canReuseBuild('SUCCEEDED') === true)
check('FAILED 不可复用', canReuseBuild('FAILED') === false)
check('TIMEOUT 不可复用', canReuseBuild('TIMEOUT') === false)
check('CANCELLED 不可复用', canReuseBuild('CANCELLED') === false)
check('RUNNING 不可复用', canReuseBuild('RUNNING') === false)
check('空状态不可复用', canReuseBuild(undefined) === false)

// 2. 取镜像产物：取最后一个（与后端「最后产出的 agent 镜像」口径一致）
const artifacts = [
  { kind: 'jar', ref: 'target/app.jar' },
  { kind: 'image', ref: 'ci-test-old' },
  { kind: 'image', ref: 'ci-test-new' },
]
check('取最后一个 image 产物', pickReusableImage(artifacts) === 'ci-test-new')

// 3. 没有镜像产物时返回空（调用方据此给出「只有检查构建」的提示）
check('只有 jar 时为空', pickReusableImage([{ kind: 'jar', ref: 'a.jar' }]) === '')
check('空列表为空', pickReusableImage([]) === '')
check('undefined 安全', pickReusableImage(undefined) === '')
check('ref 为空串时也不当产物', pickReusableImage([{ kind: 'image', ref: '  ' }]) === '')

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
