/**
 * 任务栏「按版本号筛选」的回归脚本（零依赖，替代 vitest）。
 *
 * 运行：node --experimental-strip-types --import ./tools/ts-resolve.mjs src/utils/taskVersionFilter.test.ts
 */
import {
  VERSION_UNLINKED, compareVersion, filterByVersion, normalizeVersion,
  versionFilterOptions,
} from './taskVersionFilter.ts'

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

const rows = [
  { id: 'a', version: '0.1.2' },
  { id: 'b', version: 'v0.1.10' },
  { id: 'c', version: '0.2.0' },
  { id: 'd', version: '' },
  { id: 'e' },
]

// 1. 归一化：前导 v 与空白不该造成"同一个版本两个筛选项"
check('去前导 v', normalizeVersion('v0.1.2') === '0.1.2')
check('去空白', normalizeVersion('  0.1.2  ') === '0.1.2')
check('空值归一为空串', normalizeVersion(undefined) === '')

// 2. 比较：数字段按数值，不能按字符串（否则 0.1.10 < 0.1.2）
check('0.1.10 比 0.1.2 新', compareVersion('0.1.10', '0.1.2') < 0,
  String(compareVersion('0.1.10', '0.1.2')))
check('0.2.0 比 0.1.10 新', compareVersion('0.2.0', '0.1.10') < 0)
check('位数不同也能比', compareVersion('1.0', '1.0.0') === 0)
check('带 v 前缀不影响比较', compareVersion('v0.2.0', '0.1.9') < 0)

// 3. 筛选
check('空筛选不过滤', filterByVersion(rows, '').length === 5)
check('精确匹配版本', filterByVersion(rows, '0.1.2').map((r) => r.id).join() === 'a')
check('v 前缀的版本能被无前缀值筛到',
  filterByVersion(rows, '0.1.10').map((r) => r.id).join() === 'b')
check('未关联包含空串与缺字段',
  filterByVersion(rows, VERSION_UNLINKED).map((r) => r.id).join() === 'd,e')
check('不存在的版本得到空列表', filterByVersion(rows, '9.9.9').length === 0)

// 4. 筛选项：只列真实出现过的版本，新的在前，末尾按需补"未关联"
const options = versionFilterOptions(rows)
check('首项是全部', options[0].value === '', JSON.stringify(options))
check('列出版本去重并降序',
  options.slice(1, -1).map((o) => o.value).join() === '0.2.0,0.1.10,0.1.2',
  JSON.stringify(options.slice(1, -1)))
check('末尾是未关联', options[options.length - 1].value === VERSION_UNLINKED)
check('没有未关联行时不补该选项',
  versionFilterOptions([{ version: '1.0.0' }]).length === 2)

// 5. 没有任务时不产生死选项
check('空列表只有全部', versionFilterOptions([]).length === 1)

console.log(`\n${failed === 0 ? '✅' : '❌'} ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exit(1)
}
