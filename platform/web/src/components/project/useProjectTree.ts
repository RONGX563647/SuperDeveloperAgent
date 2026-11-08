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
const loadingProjects = shallowRef(false)

export function useProjectTree(projects: () => ProjectRow[]) {
  async function buildRoots(): Promise<void> {
    loadingProjects.value = true
    const rows = projects()
    nodes.value = rows.map((p) => ({
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
    loadingProjects.value = false
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
    nodes, expanded, keyword, visibleRoots, loadingProjects,
    buildRoots, loadDir, toggle, isExpanded,
  }
}
