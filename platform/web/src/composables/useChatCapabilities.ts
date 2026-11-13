import { onMounted, ref, watch, type Ref } from 'vue'
import { listMcpServers, listSkills } from '../api/settings'

/** 对话工具栏里的一个可选能力（MCP 或 Skill）。 */
export interface CapabilityOption {
  /** 选择键：Skill 用目录名，MCP 用服务名 */
  key: string
  label: string
  description: string
  /** 设置页里的默认启用状态 */
  enabled: boolean
  /** Skill：文件数与更新时间（详情页展示） */
  fileCount?: number
  updatedAt?: number
  /** MCP：传输方式与连接配置（详情页展示） */
  transport?: 'stdio' | 'http'
  command?: string
  args?: string[]
  url?: string
  headerNames?: string[]
}

const STORAGE_PREFIX = 'agent.capabilities.'

/**
 * 对话级能力选择。
 *
 * <p>默认值来自设置页的启用开关；用户在某会话里改过之后按会话持久化到 localStorage，
 * 切换会话互不影响。发送消息时把选择带给后端（skills / mcpServers）。
 */
export function useChatCapabilities(sessionId: Ref<string>) {
  const skillOptions = ref<CapabilityOption[]>([])
  const mcpOptions = ref<CapabilityOption[]>([])
  const selectedSkills = ref<string[]>([])
  const selectedMcp = ref<string[]>([])
  const ready = ref(false)
  const loading = ref(false)

  function storageKey(): string {
    return STORAGE_PREFIX + (sessionId.value || 'default')
  }

  function applyDefaults(): void {
    selectedSkills.value = skillOptions.value.filter((o) => o.enabled).map((o) => o.key)
    selectedMcp.value = mcpOptions.value.filter((o) => o.enabled).map((o) => o.key)
  }

  /** 读取会话选择；没有记录时回落到设置页默认启用项。 */
  function loadSelection(): void {
    const keys = (list: CapabilityOption[]) => new Set(list.map((o) => o.key))
    let stored: { skills?: string[], mcp?: string[] } | null = null
    try {
      const raw = localStorage.getItem(storageKey())
      stored = raw ? JSON.parse(raw) : null
    } catch {
      stored = null
    }
    if (stored) {
      const skillKeys = keys(skillOptions.value)
      const mcpKeys = keys(mcpOptions.value)
      selectedSkills.value = (stored.skills ?? []).filter((k) => skillKeys.has(k))
      selectedMcp.value = (stored.mcp ?? []).filter((k) => mcpKeys.has(k))
    } else {
      applyDefaults()
    }
    ready.value = true
  }

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      const [skills, servers] = await Promise.all([
        listSkills().catch(() => ({ root: '', skills: [] })),
        listMcpServers().catch(() => []),
      ])
      skillOptions.value = skills.skills.map((s) => ({
        key: s.dirName,
        label: s.name,
        description: s.description,
        enabled: s.enabled,
        fileCount: s.fileCount,
        updatedAt: s.updatedAt,
      }))
      mcpOptions.value = servers.map((s) => ({
        key: s.name,
        label: s.name,
        description: s.transport === 'http' ? (s.url ?? 'http') : [s.command, ...s.args].filter(Boolean).join(' '),
        enabled: s.enabled,
        transport: s.transport,
        command: s.command ?? undefined,
        args: s.args,
        url: s.url ?? undefined,
        headerNames: s.headerNames,
      }))
    } finally {
      loading.value = false
    }
    loadSelection()
  }

  function persist(): void {
    if (!ready.value) return
    localStorage.setItem(storageKey(), JSON.stringify({
      skills: selectedSkills.value,
      mcp: selectedMcp.value,
    }))
  }

  watch([selectedSkills, selectedMcp], persist, { deep: true })
  watch(sessionId, () => {
    ready.value = false
    loadSelection()
  })
  onMounted(refresh)

  return {
    skillOptions,
    mcpOptions,
    selectedSkills,
    selectedMcp,
    ready,
    loading,
    refresh,
  }
}
