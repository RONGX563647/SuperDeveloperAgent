<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmEnvBinding, getEnvBinding } from '../../api/settings'
import type { EnvBindingView } from '../../api/settings'

/**
 * 对话内的环境绑定确认卡。
 *
 * <p>Agent 只能生成 DRAFT（env_mapping_draft），不能自己确认；本卡片的确认动作
 * 走已登录用户的 HTTP 接口（POST /env-binding/{id}/confirm），模型无法伪造。
 */
interface Props {
  projectId: string
  bindingId: string
}

const props = defineProps<Props>()

const binding = ref<EnvBindingView | null>(null)
const busy = ref(false)
const loadError = ref('')

const summary = computed(() => {
  const empty = { keys: 0, missing: [] as string[], warnings: [] as string[] }
  if (!binding.value?.mappingJson) return empty
  try {
    const mapping = JSON.parse(binding.value.mappingJson) as {
      keys?: unknown[]
      missing?: string[]
      warnings?: Array<{ message?: string }>
    }
    return {
      keys: Array.isArray(mapping.keys) ? mapping.keys.length : 0,
      missing: Array.isArray(mapping.missing) ? mapping.missing : [],
      warnings: Array.isArray(mapping.warnings)
        ? mapping.warnings.map((w) => w.message ?? '').filter(Boolean)
        : [],
    }
  } catch {
    return empty
  }
})

const confirmed = computed(() => binding.value?.status === 'CONFIRMED')
const expired = computed(() =>
  confirmed.value && !!binding.value?.expiresAt && binding.value.expiresAt < Date.now())
const expiresText = computed(() => {
  if (!binding.value?.expiresAt) return '确认后 24 小时内有效'
  return new Date(binding.value.expiresAt).toLocaleString()
})

async function load(): Promise<void> {
  loadError.value = ''
  try {
    binding.value = await getEnvBinding(props.projectId, props.bindingId)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载环境绑定失败'
  }
}

async function doConfirm(): Promise<void> {
  if (busy.value || confirmed.value) return
  busy.value = true
  try {
    const result = await confirmEnvBinding(props.projectId, props.bindingId)
    binding.value = result
    ElMessage.success('环境绑定已确认')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认环境绑定失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="binding-card">
    <div class="card-head">
      <span class="head-title">环境绑定待确认</span>
      <code class="mono head-id">{{ props.bindingId }}</code>
      <el-tag :type="confirmed ? 'success' : 'warning'" size="small" effect="dark" round>
        {{ confirmed ? '已确认' : 'DRAFT' }}
      </el-tag>
    </div>

    <div v-if="loadError" class="card-body">
      <el-alert type="error" :closable="false" show-icon :title="loadError" />
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <template v-else-if="binding">
      <div class="card-body">
        <div class="line"><span>项目 / 环境</span><b>{{ binding.projectId }} / {{ binding.env }}</b></div>
        <div class="line"><span>变量数量</span><b>{{ summary.keys }}</b></div>
        <div class="line">
          <span>缺失必填</span>
          <b>{{ summary.missing.length ? summary.missing.join('、') : '无' }}</b>
        </div>
        <div class="line"><span>映射哈希</span><b class="mono">{{ binding.mappingHash.slice(0, 16) }}…</b></div>
        <div class="line"><span>生效期限</span><b>{{ expiresText }}</b></div>
        <div v-if="summary.warnings.length" class="line warn">
          <span>告警</span>
          <b>{{ summary.warnings.join('；') }}</b>
        </div>
      </div>

      <el-alert
        v-if="confirmed && !expired"
        type="success" :closable="false" show-icon
        title="绑定已确认"
        description="请回复「继续」，Agent 会复用该 binding_id 触发发布。"
      />
      <el-alert
        v-if="expired"
        type="warning" :closable="false" show-icon
        title="绑定已过期"
        description="请让 Agent 重新生成环境绑定草案后再确认。"
      />

      <div class="card-actions">
        <el-button size="small" :disabled="busy" @click="load">刷新</el-button>
        <el-button
          size="small" type="primary"
          :disabled="confirmed || busy" :loading="busy"
          @click="doConfirm"
        >
          {{ confirmed ? '已确认' : '确认绑定' }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.binding-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  margin-top: 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-2);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.head-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--warn);
}
.head-id {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card-body .line {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.card-body .line span {
  flex: none;
  width: 90px;
  color: var(--ink-muted);
}
.card-body .line.warn b {
  color: var(--warn);
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
