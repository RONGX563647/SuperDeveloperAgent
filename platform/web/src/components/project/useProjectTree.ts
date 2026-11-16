import { computed, shallowRef } from 'vue'
import { fsLs, type FsEntry, type ProjectRow } from '../../api/project'

/** 树节点模型：project 根 / dir / file 三种；目录懒加载 fsLs。 */
export interface TreeNode {
  id: string // project:<id> | dir:<path> | file:<path>
  kind: 'project' | 'dir' | 'file'
  name: string
  path: string
  projectId: string
  project?: ProjectRow
  gitRepo: boolean
  children: TreeNode[]
  loaded: boolean
  loading: boolean
  error: string | null
}

const expanded = shallowRef<Set<string>>(new Set())
const nodes = shallowRef<TreeNode[]>([])

export function useProjectTree(projects: () => ProjectRow[]) {
  /**
   * 重建根节点时沿用旧子树的 loaded/children：
   * 刷新项目列表（新增/下线项目）不应该把已展开目录里的文件清空，
   * 否则用户看到的是「目录还展开着，但里面空了」。
   */
  function reuseSubtree(prev: TreeNode[], next: TreeNode[]): void {
    const byId = new Map<string, TreeNode>()
    const index = (list: TreeNode[]): void => {
      for (const node of list) {
        byId.set(node.id, node)
        index(node.children)
      }
    }
    index(prev)
    const walk = (list: TreeNode[]): void => {
      for (const node of list) {
        const old = byId.get(node.id)
        if (old) {
          node.children = old.children
          node.loaded = old.loaded
          node.error = old.error
        }
        walk(node.children)
      }
    }
    walk(next)
  }

  /** 已展开但仍未加载的目录（新建节点）补一次懒加载。 */
  function loadExpanded(list: TreeNode[]): void {
    for (const node of list) {
      if (node.kind === 'dir' && expanded.value.has(node.id) && !node.loaded) {
        void loadDir(node)
      }
      if (node.children.length) loadExpanded(node.children)
    }
  }

  function buildRoots(): void {
    const rows = projects()
    const next = rows.map((p) => ({
      id: `project:${p.id}`,
      kind: 'project' as const,
      name: p.name,
      path: p.localPath ?? '',
      projectId: p.id,
      project: p,
      gitRepo: !!p.repoUrl,
      children: p.localPath ? [{
        id: `dir:${p.localPath}`,
        kind: 'dir' as const,
        name: p.localPath.split(/[\\/]/).pop() ?? p.localPath,
        path: p.localPath,
        projectId: p.id,
        project: p,
        gitRepo: true,
        children: [],
        loaded: false,
        loading: false,
        error: null,
      }] : [],
      loaded: true,
      loading: false,
      error: null,
    }))
    reuseSubtree(nodes.value, next)
    nodes.value = next
    loadExpanded(next)
  }

  async function loadDir(node: TreeNode): Promise<void> {
    if (node.loaded || node.loading) return
    node.loading = true
    node.error = null
    try {
      const listing = await fsLs(node.path)
      node.children = listing.entries.map((entry: FsEntry) => ({
        id: `${entry.directory ? 'dir' : 'file'}:${entry.path}`,
        kind: entry.directory ? ('dir' as const) : ('file' as const),
        name: entry.name,
        path: entry.path,
        projectId: node.projectId,
        gitRepo: entry.gitRepo,
        children: [],
        loaded: false,
        loading: false,
        error: null,
      }))
      node.loaded = true
    } catch (e) {
      node.error = e instanceof Error ? e.message : '加载失败'
    } finally {
      node.loading = false
    }
  }

  /** 目录加载失败后的重试：清掉错误重新拉一次（不改变展开状态）。 */
  async function retryDir(node: TreeNode): Promise<void> {
    if (node.loading) return
    node.loaded = false
    node.error = null
    await loadDir(node)
  }

  function toggle(node: TreeNode): void {
    const next = new Set(expanded.value)
    if (next.has(node.id)) {
      next.delete(node.id)
    } else {
      next.add(node.id)
      if (node.kind === 'dir' && !node.loaded) void loadDir(node)
    }
    expanded.value = next
  }

  function isExpanded(node: TreeNode): boolean {
    return expanded.value.has(node.id)
  }

  const keyword = shallowRef('')
  const visibleRoots = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    if (!kw) return nodes.value
    return nodes.value.filter(
      (n) => n.name.toLowerCase().includes(kw)
        || n.path.toLowerCase().includes(kw)
        || n.projectId.toLowerCase().includes(kw),
    )
  })

  return {
    nodes, expanded, keyword, visibleRoots,
    buildRoots, loadDir, retryDir, toggle, isExpanded,
  }
}
