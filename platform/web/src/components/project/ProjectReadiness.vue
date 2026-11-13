<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, CircleCloseFilled, RefreshRight } from '@element-plus/icons-vue'
import {
  projectReadiness, scaffoldApply,
  type Readiness, type ReadinessStep,
} from '../../api/project'

/**
 * 首次发布就绪清单（项目操作台顶部）。
 *
 * <p>把原先散落在接入页/设置页/流水线页的前置条件收拢成一张表：
 * 每一项给出当前状态与唯一的修复入口，用户不需要记住"还差什么、去哪里改"。
 * 只读数据来自 /api/v1/projects/{id}/readiness；修复动作走各页既有接口。
 */
const props = defineProps<{ projectId: string }>()

const router = useRouter()
const data = ref<Readiness | null>(null)
const loading = ref(false)
const fixingKey = ref('')
/** 默认折叠成一行摘要：操作台主按钮已经能一键发布，清单只在需要排查时展开。 */
const open = ref(false)

async function load(): Promise<void> {
  if (!props.projectId) return
  loading.value = true
  try {
    data.value = await projectReadiness(props.projectId, 'test')
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

watch(() => props.projectId, () => {
  open.value = false
  void load()
})
onMounted(load)

/** 供父组件在「一键发布」被前置条件挡住时自动展开。 */
function expand(): void {
  open.value = true
}
defineExpose({ expand, reload: load })

/** 结果项（首次发布）不参与 ready，只是展示当前跑到哪一步。 */
const badgeText = computed(() => {
  if (!data.value) return ''
  if (data.value.ready) return data.value.totalCount > 0 && data.value.steps.every(s => s.ok)
    ? '全部就绪'
    : '可以发布'
  return `${data.value.readyCount}/${data.value.totalCount} 项就绪`
})

const badgeClass = computed(() => {
  if (!data.value) return ''
  return data.value.ready ? 'ok' : 'warn'
})

/** 折叠态的一行摘要：只补徽标没表达的信息（第一个缺项），避免重复计数。 */
const summary = computed(() => {
  if (!data.value) return loading.value ? '检查中…' : ''
  if (data.value.ready) {
    return data.value.steps.every(s => s.ok) ? '全部就绪，可直接发布' : '可以发布到 test'
  }
  const missing = data.value.steps.find(s => s.required && !s.ok)
  return missing ? `缺：${missing.label}` : ''
})

async function runFix(step: ReadinessStep): Promise<void> {
  const fix = step.fix
  if (!fix) return
  if (fix.kind === 'scaffold') {
    fixingKey.value = step.key
    try {
      const result = await scaffoldApply(props.projectId, fix.target || 'auto', true, 'platform')
      if (!result.written.length) {
        ElMessage.warning(result.note ?? '没有生成定义，请检查仓库结构')
      } else {
        ElMessage.success('已生成流水线定义（平台侧），可直接发布')
      }
      await load()
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '生成失败')
    } finally {
      fixingKey.value = ''
    }
    return
  }
  void router.push(fix.target)
}
</script>

<template>
  <section v-if="data || loading" class="po-card readiness" :class="badgeClass">
    <div class="rd-head">
      <h3 class="po-card-title" style="margin: 0">首次发布准备</h3>
      <span class="rd-badge" :class="badgeClass">{{ badgeText }}</span>
      <span class="rd-summary">{{ summary }}</span>
      <button type="button" class="rd-toggle" @click="open = !open">
        {{ open ? '收起' : '展开' }}
      </button>
      <button type="button" class="rd-refresh" :disabled="loading" title="刷新" @click="load">
        <el-icon><RefreshRight /></el-icon>
      </button>
    </div>

    <template v-if="open">
      <p class="rd-sub">
        按顺序补齐下列前置条件，然后在流水线页发布 <code class="mono">test</code>。
      </p>

      <p v-if="loading && !data" class="rd-hint">检查中…</p>
      <div v-else-if="data" class="rd-list">
        <div
          v-for="s in data.steps"
          :key="s.key"
          class="rd-row"
          :class="{ ok: s.ok, optional: !s.required }"
        >
          <el-icon class="rd-icon">
            <CircleCheckFilled v-if="s.ok" />
            <CircleCloseFilled v-else />
          </el-icon>
          <span class="rd-label">{{ s.label }}</span>
          <span class="rd-detail" :title="s.detail">{{ s.detail }}</span>
          <span v-if="!s.required" class="rd-tag">结果</span>
          <button
            v-else-if="!s.ok && s.fix"
            type="button"
            class="rd-fix"
            :disabled="fixingKey === s.key"
            @click="runFix(s)"
          >
            {{ fixingKey === s.key ? '处理中…' : s.fix.label }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.readiness {
  margin-bottom: 14px;
}
.rd-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.rd-badge {
  padding: 1px 9px;
  font-size: 11.5px;
  font-weight: 600;
  border-radius: var(--radius-pill);
}
.rd-badge.ok {
  color: var(--ok);
  background: color-mix(in srgb, var(--ok) 12%, transparent);
}
.rd-badge.warn {
  color: var(--warn);
  background: var(--warn-soft);
}
.rd-refresh {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.rd-summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rd-toggle {
  flex: none;
  padding: 0 4px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}
.rd-refresh:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.rd-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.rd-sub {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--ink-faint);
}
.rd-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-faint);
}
.rd-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.rd-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 12.5px;
  border-top: 1px solid var(--line-soft);
}
.rd-row:first-child {
  border-top: 0;
}
.rd-icon {
  flex: none;
  font-size: 15px;
  color: var(--danger);
}
.rd-row.ok .rd-icon {
  color: var(--ok);
}
.rd-label {
  flex: none;
  width: 84px;
  color: var(--ink);
}
.rd-detail {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rd-row.optional .rd-detail {
  color: var(--ink-faint);
}
.rd-tag {
  flex: none;
  padding: 0 7px;
  font-size: 10.5px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.rd-fix {
  flex: none;
  height: 24px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--accent-soft);
  border: 1px solid color-mix(in srgb, var(--accent) 28%, transparent);
  border-radius: var(--radius-sm);
}
.rd-fix:hover:not(:disabled) {
  background: color-mix(in srgb, var(--accent) 18%, transparent);
}
.rd-fix:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
