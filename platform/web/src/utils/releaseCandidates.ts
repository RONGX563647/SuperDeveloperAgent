/**
 * 生产发布的候选版本：只来自测试环境已经构建并部署成功的镜像。
 *
 * <p>生产不再让用户按 git 分支/提交/版本号自己拼发布对象——那条路径会选出
 * 从未在测试跑过的提交。候选来自镜像账本（生产视角已经是测试账本），
 * 每个候选同时带出镜像 tag 与对应的 git 标签，便于核对"发的是哪一版"。
 */
import type { HistoryItem, ImageVersionView } from '../api/pipeline.ts'

export interface ReleaseCandidate {
  /** 镜像账本 key，也是部署时传给后端的 tag（ci-test-xxxx）。 */
  imageTag: string
  /** 展示版本：用户填写的版本号，或 test-vN / prod-vN。 */
  version: string
  /** 对应的 git 标签（x.y.z）；没有正式发布过时为空。 */
  gitTag: string
  /** PUSHED / LOCAL / FAILED：标签是否真的在远端可见。 */
  tagStatus: string
  gitSha: string
  branch: string
  runId: string
  status: string
  createdAt: number
  deployedAt: number
  current: boolean
  previous: boolean
}

const SEMVER = /^\d{1,4}\.\d{1,4}\.\d{1,4}$/

/** 历史里带 PUSHED/LOCAL 标签、且确实在版本线上发布的记录，才算 git 标签证据。 */
function historyGitTag(h: HistoryItem | undefined): { tag: string, status: string } {
  const tag = (h?.version ?? '').trim()
  if (!SEMVER.test(tag)) return { tag: '', status: '' }
  if (h?.tagStatus === 'PUSHED' || h?.tagStatus === 'LOCAL') {
    return { tag, status: h.tagStatus }
  }
  return { tag: '', status: '' }
}

/**
 * 组装候选：一个测试镜像一条；按账本顺序（最近部署在前）。
 *
 * <p>没有 gitSha 的记录无法做生产证据校验（DeployGate 按 sha 找测试成功案例），
 * 直接剔除，避免"看得见、点不动"。
 */
export function buildReleaseCandidates(
  images: ImageVersionView[],
  history: HistoryItem[],
  gitTags: string[] = [],
): ReleaseCandidate[] {
  const byRun = new Map(history.filter((h) => h.runId).map((h) => [h.runId, h]))
  const bySha = new Map<string, HistoryItem>()
  for (const h of history) {
    if (h.gitSha && !bySha.has(h.gitSha)) bySha.set(h.gitSha, h)
  }
  const tags = new Set((gitTags ?? []).map((t) => t.trim()))
  const rows: ReleaseCandidate[] = []
  for (const img of images ?? []) {
    const imageTag = (img.tag ?? '').trim()
    const gitSha = (img.gitSha ?? '').trim()
    if (!imageTag || gitSha.length < 7) continue
    const h = (img.runId && byRun.get(img.runId)) || bySha.get(gitSha)
    const evidence = historyGitTag(h)
    // 历史没记标签时，镜像版本号本身恰好是已有的 git 标签，也认（旧数据兼容）
    const version = (img.version ?? '').trim()
    const fallback = SEMVER.test(version) && tags.has(version)
      ? { tag: version, status: '' }
      : { tag: '', status: '' }
    const git = evidence.tag ? evidence : fallback
    rows.push({
      imageTag,
      version,
      gitTag: git.tag,
      tagStatus: git.status,
      gitSha,
      branch: (h?.branch ?? '').trim(),
      runId: (img.runId ?? '').trim(),
      status: (img.status ?? '').trim(),
      createdAt: img.createdAt || 0,
      deployedAt: img.deployedAt || 0,
      current: !!img.current,
      previous: !!img.previous,
    })
  }
  return rows
}

/** 下拉里的一行：展示版本 + 镜像 tag + git 标签（没有则明确写"无"）。 */
export function releaseCandidateLabel(c: ReleaseCandidate): string {
  return `${c.version || c.imageTag} · ${c.imageTag} · tag ${c.gitTag || '无'}`
}
