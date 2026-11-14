<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listProjects, projectEnvs, type ProjectRow } from '../../api/project'
import { appStore } from '../../stores/app'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 全局上下文切换（项目 / 环境）。
 *
 * <p>项目与环境只有一个真相：`appStore.projectId` / `appStore.env`。
 * 顶栏放这一个切换器后，对话页与流水线页不再各自维护下拉，
 * 切页后上下文不会变、也不会出现两个地方显示不同项目。
 *
 * <p>项目列表在模块级缓存：多个页面共享一次请求，切页不重复拉。
 */
const props = withDefaults(defineProps<{
  /** 是否显示环境切换（流水线页有自己的环境状态带，只需项目切换）。 */
  env?: boolean
}>(), { env: true })

let projectCache: ProjectRow[] | null = null

const projects = ref<ProjectRow[]>([])
const envOptions = ref<string[]>(['dev', 'test', 'prod'])

function ensureProject(): void {
  if (!projects.value.length) return
  if (!projects.value.some((p) => p.id === appStore.projectId)) {
    appStore.projectId = projects.value[0].id
  }
}

onMounted(async () => {
  if (projectCache) {
    projects.value = projectCache
    ensureProject()
    return
  }
  try {
    projectCache = await listProjects()
    projects.value = projectCache
    ensureProject()
  } catch {
    // 接口不可用时保持当前 projectId，不阻塞页面
    projects.value = []
  }
})

/** 环境选项来自项目真实配置；接口失败时退回 dev/test/prod。 */
async function loadEnvs(id: string): Promise<void> {
  if (!props.env || !id) return
  try {
    const rows = await projectEnvs(id)
    const list = rows.map((r) => r.env).filter(Boolean)
    envOptions.value = list.length ? list : ['dev', 'test', 'prod']
    if (list.length && !list.includes(appStore.env)) {
      appStore.env = list.includes('test') ? 'test' : list[0]
    }
  } catch {
    envOptions.value = ['dev', 'test', 'prod']
  }
}

watch(() => appStore.projectId, (id) => void loadEnvs(id), { immediate: true })

const envModel = computed({
  get: () => appStore.env,
  set: (value: string) => { appStore.env = value },
})

function onProject(id: string): void {
  if (id === appStore.projectId) return
  // 必须走 switchProject：它会换到该项目最近的会话（没有则新建），
  // 直接改 projectId 会让同一个 sessionId 带着 A 项目的上下文进入 B 项目。
  appStore.switchProject(id)
}
</script>

<template>
  <div class="ctx-switch">
    <el-select
      :model-value="appStore.projectId"
      size="small"
      class="cs-project"
      placeholder="选择项目"
      filterable
      aria-label="项目"
      @update:model-value="onProject"
    >
      <el-option
        v-for="p in projects" :key="p.id"
        :label="p.name" :value="p.id"
      />
    </el-select>
    <el-select
      v-if="env"
      v-model="envModel"
      size="small"
      class="cs-env"
      aria-label="环境"
    >
      <el-option v-for="e in envOptions" :key="e" :label="envLabel(e)" :value="e" />
    </el-select>
  </div>
</template>

<style scoped>
.ctx-switch {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  margin-left: 6px;
}
.cs-project {
  width: 132px;
}
.cs-env {
  width: 92px;
}
.ctx-switch :deep(.el-select__wrapper) {
  min-height: 26px;
  font-size: var(--text-xs);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
  box-shadow: none;
}
.ctx-switch :deep(.el-select__wrapper:hover) {
  background: var(--surface-hover);
}
/* 手机：环境切换收进内容区，顶栏只留项目，避免横向溢出 */
@media (max-width: 600px) {
  .cs-project {
    width: 104px;
  }
  .cs-env {
    display: none;
  }
}
</style>
