/**
 * 任务栏「按版本号筛选」的纯逻辑。
 *
 * <p>抽出来的原因：筛选与排序要么写进 SFC 就测不到（本仓库前端测试是零依赖的
 * `check()` 脚本，不挂 vitest，取不到组件内部的 computed），要么散在模板表达式里
 * 无法复用。放在 utils 里既能被单测覆盖，也能被看板与列表共用。
 */

/** 「未关联版本」的筛选值；任务 `version` 为空时归到这一档。 */
export const VERSION_UNLINKED = '__none__'

/** 只关心 version 字段的行。 */
interface Versioned {
  version?: string | null
}

/** 规整版本号：去空白、去前导 v（`v0.1.2` 与 `0.1.2` 视为同一个版本）。 */
export function normalizeVersion(raw: string | null | undefined): string {
  const text = (raw ?? '').trim()
  return text.replace(/^[vV]/, '')
}

/**
 * 版本号比较：按 `.` 分段，数字段按数值比，非数字段退回字符串比。
 *
 * <p>不支持完整 semver 语义（预发布标签等），因为任务栏只需要"新版本在前"的稳定顺序；
 * 过度解析反而会让 `0.2` 与 `0.2.0` 这类实际存在的写法排序错乱。
 *
 * @returns 负数表示 a 更新（用于降序排序时把新版本排前面）
 */
export function compareVersion(a: string, b: string): number {
  const left = normalizeVersion(a).split('.')
  const right = normalizeVersion(b).split('.')
  const size = Math.max(left.length, right.length)
  for (let i = 0; i < size; i++) {
    const l = left[i] ?? ''
    const r = right[i] ?? ''
    if (l === r) continue
    const ln = Number(l)
    const rn = Number(r)
    if (Number.isFinite(ln) && Number.isFinite(rn)) return rn - ln
    // 一边是数字一边不是：数字段视为更"具体"，排在前面
    if (Number.isFinite(ln)) return -1
    if (Number.isFinite(rn)) return 1
    return r.localeCompare(l, 'zh-CN')
  }
  return 0
}

/**
 * 按版本筛选行。
 *
 * @param filter 空串 = 不过滤；{@link VERSION_UNLINKED} = 只看未关联；
 *               其它值 = 版本号精确匹配（经 {@link normalizeVersion} 归一）
 */
export function filterByVersion<T extends Versioned>(rows: T[], filter: string): T[] {
  if (!filter) return rows
  if (filter === VERSION_UNLINKED) {
    return rows.filter((row) => !normalizeVersion(row.version))
  }
  return rows.filter((row) => normalizeVersion(row.version) === filter)
}

/** 一个筛选项。 */
export interface VersionOption {
  label: string
  value: string
}

/**
 * 生成版本筛选项。
 *
 * <p>只列**当前结果里真实出现过的版本**（新版本在前），再按需补「未关联」。
 * 不把所有 git 标签都铺进来：那些没有任何任务的版本只会制造"选了却是空列表"的死选项。
 */
export function versionFilterOptions(rows: Versioned[]): VersionOption[] {
  const seen = new Set<string>()
  let hasUnlinked = false
  for (const row of rows) {
    const version = normalizeVersion(row.version)
    if (version) seen.add(version)
    else hasUnlinked = true
  }
  const options: VersionOption[] = [{ label: '全部版本', value: '' }]
  for (const version of [...seen].sort(compareVersion)) {
    options.push({ label: version, value: version })
  }
  if (hasUnlinked) {
    options.push({ label: '未关联版本', value: VERSION_UNLINKED })
  }
  return options
}
