<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getEffectiveEnvironment, type EffectiveEnvironmentView } from '../../api/settings'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 运行前环境摘要：把「设置 → 环境模板/项目覆盖」里真正会注入容器的结果摊开给用户看。
 *
 * <p>变量、网络、端口、中间件健康状态同时出现在触发区，避免"点了运行才发现少变量"。
 */
const props = defineProps<{ projectId: string, env: string }>()
const router = useRouter()
const effective = ref<EffectiveEnvironmentView | null>(null)
const loading = ref(false)

/** 只认被项目变量引用的资源：共享/其它项目的容器不计入健康。 */
const referenced = computed(() =>
  (effective.value?.resources ?? []).filter((r) => r.referenced !== false))
const missing = computed(() => referenced.value.filter((r) => r.status === 'MISSING'))
const stopped = computed(() =>
  referenced.value.filter((r) => r.status === 'STOPPED' || r.status === 'UNKNOWN').length)
const running = computed(() => {
  const view = effective.value
  if (!view) return 0
  return view.referencedRunningCount ?? referenced.value.filter((r) => r.status === 'RUNNING').length
})
const total = computed(() => {
  const view = effective.value
  if (!view) return 0
  return view.referencedCount ?? referenced.value.length
})
const unreferenced = computed(() =>
  (effective.value?.resources.length ?? 0) - referenced.value.length)

async function load(): Promise<void> {
  if (!props.projectId) {
    effective.value = null
    return
  }
  loading.value = true
  try {
    effective.value = await getEffectiveEnvironment(props.projectId, props.env)
  } catch {
    effective.value = null
  } finally {
    loading.value = false
  }
}

function goSettings(): void {
  void router.push({ path: '/settings', query: { section: 'envs' } })
}

watch(() => [props.projectId, props.env], () => void load(), { immediate: true })
defineExpose({ load })
</script>

<template>
  <div v-if="effective" class="env-summary" :class="{ danger: missing.length > 0 }">
    <span class="es-label">本次注入</span>
    <span class="es-item">
      变量 <b>{{ effective.vars.length }}</b>
      <em>模板 {{ effective.templateCount }} / 覆盖 {{ effective.overrideCount }}</em>
    </span>
    <span class="es-item">网络 <b>{{ effective.network || '默认' }}</b></span>
    <span class="es-item">
      端口 <b>{{ effective.defaultPort || '未配置' }}</b>
      <em>{{ effective.healthPath || '/healthz' }}</em>
    </span>
    <span class="es-item" :class="{ bad: missing.length > 0 }">
      所需中间件 <b>{{ running }}/{{ total }}</b> 运行
      <em v-if="stopped">（{{ stopped }} 个未启动，部署时自动启动）</em>
      <em v-else-if="unreferenced">（另 {{ unreferenced }} 个环境共享，不计入健康）</em>
    </span>
    <span v-if="missing.length" class="es-warn">
      {{ missing.map((r) => `${r.name}（缺${r.missingFields.join('/')}）`).join('、') }}，
      部署会被拦截，请到「环境模板」补齐连接信息
    </span>
    <button type="button" class="es-link" @click="goSettings">
      配置 {{ envLabel(effective.env) }}
    </button>
    <span v-if="loading" class="es-loading">刷新中…</span>
  </div>
</template>

<style scoped>
.env-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 7px 11px;
  font-size: 11px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.env-summary.danger {
  border-color: color-mix(in srgb, var(--danger) 40%, transparent);
}
.es-label {
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--ink-faint);
  text-transform: uppercase;
}
.es-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: var(--ink-muted);
}
.es-item b {
  font-weight: 600;
  color: var(--ink);
}
.es-item em {
  font-size: 10px;
  font-style: normal;
  color: var(--ink-faint);
}
.es-item.bad b {
  color: var(--danger);
}
.es-warn {
  padding: 1px 7px;
  font-size: 10px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-pill);
}
.es-link {
  margin-left: auto;
  padding: 0;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
.es-loading {
  font-size: 10px;
  color: var(--ink-faint);
}
</style>
