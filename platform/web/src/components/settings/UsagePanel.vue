<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getUsageSummary, type TokenUsageTotals, type UsageSummaryView,
} from '../../api/settings'
import { formatTokens } from '../../utils/deepseekPricing'

/**
 * token 用量统计：把每次模型调用的用量聚合成一屏「独立日志」。
 *
 * 数据来自后端 token_usage 明细库（/usage/summary），与对话事件无关，
 * 刷新页面或换设备都能看到同一份累计。
 */
const days = ref(30)
const loading = ref(false)
const data = ref<UsageSummaryView | null>(null)

const emptyTotals: TokenUsageTotals = {
  calls: 0, inputTokens: 0, cachedTokens: 0, outputTokens: 0, totalTokens: 0, lastAt: 0,
}
const totals = computed(() => data.value?.totals ?? emptyTotals)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await getUsageSummary(days.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '用量统计加载失败')
  } finally {
    loading.value = false
  }
}

async function changeDays(value: number): Promise<void> {
  days.value = value
  await load()
}

function hitRate(): string {
  const input = totals.value.inputTokens
  if (!input) return '—'
  return `${Math.round((totals.value.cachedTokens / input) * 100)}%`
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">用量统计</div>
        <div class="panel-desc">
          每次模型调用结束后按会话、模型、项目聚合；明细同时写入 logs/token-usage.log。
        </div>
      </div>
      <div class="head-actions">
        <el-select :model-value="days" size="small" class="days" @change="changeDays">
          <el-option :value="7" label="近 7 天" />
          <el-option :value="30" label="近 30 天" />
          <el-option :value="90" label="近 90 天" />
        </el-select>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-loading="loading" class="panel-body">
      <div class="stat-grid">
        <div class="stat"><span>调用次数</span><b>{{ totals.calls }}</b></div>
        <div class="stat"><span>输入 tokens</span><b>{{ formatTokens(totals.inputTokens) }}</b></div>
        <div class="stat"><span>缓存命中</span><b>{{ formatTokens(totals.cachedTokens) }} <em>{{ hitRate() }}</em></b></div>
        <div class="stat"><span>输出 tokens</span><b>{{ formatTokens(totals.outputTokens) }}</b></div>
        <div class="stat"><span>合计 tokens</span><b>{{ formatTokens(totals.totalTokens) }}</b></div>
      </div>

      <div class="section-title">按模型</div>
      <el-table v-if="data?.byModel.length" :data="data.byModel" size="small" class="table">
        <el-table-column prop="modelName" label="模型" min-width="160" show-overflow-tooltip />
        <el-table-column prop="calls" label="调用" width="80" align="right" />
        <el-table-column label="输入" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.inputTokens) }}</template>
        </el-table-column>
        <el-table-column label="命中" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.cachedTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.outputTokens) }}</template>
        </el-table-column>
        <el-table-column label="合计" width="110" align="right">
          <template #default="{ row }">{{ formatTokens(row.totalTokens) }}</template>
        </el-table-column>
      </el-table>
      <p v-else class="empty">所选区间还没有调用记录。</p>

      <div class="section-title">按天</div>
      <el-table v-if="data?.daily.length" :data="data.daily" size="small" class="table">
        <el-table-column prop="day" label="日期" width="120" />
        <el-table-column prop="calls" label="调用" width="80" align="right" />
        <el-table-column label="输入" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.inputTokens) }}</template>
        </el-table-column>
        <el-table-column label="缓存命中" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.cachedTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出" width="100" align="right">
          <template #default="{ row }">{{ formatTokens(row.outputTokens) }}</template>
        </el-table-column>
        <el-table-column label="合计" width="110" align="right">
          <template #default="{ row }">{{ formatTokens(row.totalTokens) }}</template>
        </el-table-column>
      </el-table>
      <p v-else class="empty">按天聚合暂无数据。</p>

      <template v-if="data?.byProject.length">
        <div class="section-title">按项目</div>
        <div class="project-list">
          <div v-for="row in data.byProject" :key="row.projectId" class="project-row">
            <span class="mono project-id">{{ row.projectId }}</span>
            <span class="mono project-meta">
              {{ row.calls }} 次 · {{ formatTokens(row.inputTokens) }} in ·
              {{ formatTokens(row.outputTokens) }} out ·
              <b>{{ formatTokens(row.totalTokens) }}</b>
            </span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.panel-title {
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.panel-desc {
  max-width: 46rem;
  margin-top: 4px;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.days {
  width: 108px;
}
.panel-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 160px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.stat span {
  font-size: 11px;
  color: var(--ink-muted);
}
.stat b {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.stat b em {
  margin-left: 4px;
  font-size: 10.5px;
  font-style: normal;
  font-weight: 400;
  color: var(--ink-faint);
}
.section-title {
  padding: 12px 0 2px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-soft);
}
.table {
  background: transparent;
}
.empty {
  margin: 0;
  padding: 12px 2px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.project-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.project-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  font-size: 11px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.project-id {
  color: var(--ink);
}
.project-meta {
  color: var(--ink-muted);
  text-align: right;
}
.project-meta b {
  color: var(--ink);
}

@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
