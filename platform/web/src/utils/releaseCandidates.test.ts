/**
 * 生产发布候选版本的零依赖回归脚本。
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/releaseCandidates.test.ts
 */
import { buildReleaseCandidates, releaseCandidateLabel } from './releaseCandidates.ts'
import type { HistoryItem, ImageVersionView } from '../api/pipeline.ts'

let passed = 0
let failed = 0
function check(name: string, condition: boolean, detail = ''): void {
  if (condition) { passed++; console.log(`  ✓ ${name}`) }
  else { failed++; console.error(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`) }
}

function image(over: Partial<ImageVersionView>): ImageVersionView {
  return {
    tag: 'ci-test-3981a7c', digest: '', sizeBytes: 0, version: 'test-v43',
    runId: 'run-43', gitSha: '3981a7c1e2f3'.padEnd(40, '0'), status: 'SUCCEEDED',
    createdAt: 100, deployedAt: 200, deployedBy: 'ui:admin', current: true,
    previous: false,
    ...over,
  }
}
function history(over: Partial<HistoryItem>): HistoryItem {
  return {
    runId: 'run-43', projectId: 'RY', env: 'test', pipelineId: 'release',
    gitSha: '3981a7c1e2f3'.padEnd(40, '0'), branch: '0.1.0-snapshot', version: '0.1.0',
    tagStatus: 'PUSHED', status: 'SUCCEEDED', createdAt: 100, seq: 43,
    stageCount: 5, durationMs: 1000,
    ...over,
  }
}

// 1. 基本组装：镜像 tag + git tag 都在，且按账本顺序
const rows = buildReleaseCandidates([image({})], [history({})], ['0.1.0'])
check('一条测试镜像 = 一个候选', rows.length === 1)
check('镜像 tag 保留', rows[0].imageTag === 'ci-test-3981a7c', rows[0].imageTag)
check('git tag 来自成功打标签的那次运行', rows[0].gitTag === '0.1.0', rows[0].gitTag)
check('展示文案含版本 / 镜像 tag / git tag',
  releaseCandidateLabel(rows[0]) === 'test-v43 · ci-test-3981a7c · tag 0.1.0',
  releaseCandidateLabel(rows[0]))

// 2. 没有 git tag 的测试版本仍可选，但 gitTag 为空（不把 test-vN 当 git 标签）
const noTag = buildReleaseCandidates([image({ version: 'test-v44', runId: 'run-44' })],
  [history({ runId: 'run-44', version: '', tagStatus: '' })], [])
check('无标签时 gitTag 为空', noTag[0].gitTag === '')
check('无标签时仍有候选', noTag.length === 1)

// 3. 旧数据兼容：镜像版本号本身就是已存在的 git 标签
const old = buildReleaseCandidates([image({ version: '0.1.1', runId: 'run-45' })],
  [history({ runId: 'run-45', version: '', tagStatus: '' })], ['0.1.1'])
check('旧数据按已有标签补全', old[0].gitTag === '0.1.1', old[0].gitTag)

// 4. 没有 gitSha 的记录剔除：生产证据校验无法进行
const broken = buildReleaseCandidates([image({ gitSha: '' })], [], [])
check('无 gitSha 不进候选', broken.length === 0)

// 5. 本地标签（未推送）也带出，但保留状态让界面提示
const local = buildReleaseCandidates([image({})], [history({ tagStatus: 'LOCAL' })], [])
check('LOCAL 标签仍带出', local[0].gitTag === '0.1.0' && local[0].tagStatus === 'LOCAL')

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
