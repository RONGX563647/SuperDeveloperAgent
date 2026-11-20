<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listSandboxImages, type SandboxImage } from '../../api/settings'
import {
  failedCount, retryText, sourceText, statusLabel, statusTagType
} from './imageReadiness'

/**
 * 工具链镜像就绪（只读）。
 *
 * <p>构建前置阶段会按 host + image 记状态并退避：镜像坏了不必等下一次构建跑到一半
 * 才发现。这里只读不拉取——查询接口不该顺手下载几个 G。
 */
const rows = ref<SandboxImage[]>([])
const loading = ref(false)
const loaded = ref(false)

const failed = computed(() => failedCount(rows.value))
const ready = computed(() => rows.value.length - failed.value)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listSandboxImages()
    loaded.value = true
  } catch (error) {
    // 进程模式（无 Docker/无库）也会走到这里：不弹错，面板显示空态即可
    rows.value = []
    loaded.value = true
    ElMessage.error(error instanceof Error ? error.message : '镜像状态读取失败')
  } finally {
    loading.value = false
  }
}

function updatedText(row: SandboxImage): string {
  if (!row.updatedAt) return '—'
  const date = new Date(row.updatedAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="sandbox-image-panel" v-loading="loading">
    <header class="panel-head">
      <div class="head-top">
        <h2>工具链镜像</h2>
        <el-button :icon="Refresh" size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p>
        构建用的镜像（默认 <code>maven:3.9-eclipse-temurin-21</code>）在本机的就绪状态。
        流水线开跑前会统一点亮所有阶段要用的镜像；失败会按镜像退避，退避窗口内直接复用上次结论，
        不会让每次构建重新白等一轮超时。
      </p>
    </header>

    <el-alert
      v-if="failed > 0"
      type="warning"
      :closable="false"
      show-icon
      :title="`${failed} 个镜像未就绪`"
      :description="`这 ${failed} 个镜像在退避窗口内会让构建快速失败；排查网络/Docker 守护，或配好本地缓存源（agent.sandbox.pull-mirrors）。`"
      class="warn-alert"
    />

    <div v-if="rows.length > 0" class="summary">
      <span class="dot ok" /> 已就绪 {{ ready }}
      <span class="dot bad" /> 失败 {{ failed }}
      <span class="hint">共 {{ rows.length }} 条记录（按主机 + 镜像）</span>
    </div>

    <el-table v-if="rows.length > 0" :data="rows" size="small" class="image-table">
      <el-table-column prop="host" label="主机" min-width="140" show-overflow-tooltip />
      <el-table-column prop="image" label="镜像" min-width="240" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row)" size="small" disable-transitions>
            {{ statusLabel(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ sourceText(row) }}</template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试" width="80" align="right" />
      <el-table-column label="下次重试" width="130">
        <template #default="{ row }">{{ retryText(row) }}</template>
      </el-table-column>
      <el-table-column prop="lastError" label="最后错误" min-width="260" show-overflow-tooltip />
      <el-table-column label="更新时间" width="110">
        <template #default="{ row }">{{ updatedText(row) }}</template>
      </el-table-column>
    </el-table>

    <el-empty
      v-else-if="loaded"
      :image-size="72"
      description="还没有镜像记录：本机还没跑过构建，或当前是宿主进程模式（无 Docker）"
    />
  </div>
</template>

<style scoped>
.sandbox-image-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.panel-head h2 {
  margin: 0;
  font-size: var(--text-lg, 16px);
  font-weight: 650;
  color: var(--ink);
}
.panel-head p {
  margin: 6px 0 0;
  font-size: var(--text-xs, 12px);
  line-height: 1.7;
  color: var(--ink-muted);
}
.panel-head code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--surface-sunken, rgba(0, 0, 0, .04));
  font-size: var(--text-xs, 12px);
}
.warn-alert {
  max-width: 760px;
}
.summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-xs, 12px);
  color: var(--ink-muted);
}
.summary .hint {
  margin-left: 8px;
  opacity: .8;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.dot.ok {
  background: var(--success, #22a06b);
}
.dot.bad {
  margin-left: 10px;
  background: var(--danger, #d64545);
}
.image-table {
  width: 100%;
}
</style>
