<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Connection, MagicStick, Refresh, Setting, Warning } from '@element-plus/icons-vue'
import type { CapabilityOption } from '../../composables/useChatCapabilities'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * <p>只回答一个问题：**这一轮对话，Agent 能用哪些 Skill / MCP？**
 * 所以这里是一张「开关清单」而不是市场：每行一个能力（名称 + 什么时候会用 + 开关），
 * 按 Skill / MCP 分两张卡；不可用的连接态标出来，避免"开了但用不了"。
 * 会话选择与设置页默认互不影响，开关立即用于下一轮请求。
 */
const props = defineProps<{
  skillOptions: CapabilityOption[]
  mcpOptions: CapabilityOption[]
  skills: string[]
  mcp: string[]
  ready: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:skills', value: string[]): void
  (e: 'update:mcp', value: string[]): void
  (e: 'refresh'): void
}>()

const router = useRouter()

const skillOn = computed(() => props.skills.length)
const mcpOn = computed(() => props.mcp.length)
const total = computed(() => props.skillOptions.length + props.mcpOptions.length)

/** 已启用项会整段进系统提示词；超过这个数量给一句成本提醒。 */
const noisy = computed(() => skillOn.value + mcpOn.value > 10)

function isOn(kind: 'skill' | 'mcp', key: string): boolean {
  return (kind === 'skill' ? props.skills : props.mcp).includes(key)
}

function toggle(kind: 'skill' | 'mcp', key: string, on: boolean): void {
  const current = kind === 'skill' ? props.skills : props.mcp
  const next = on ? [...current, key] : current.filter((k) => k !== key)
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

function toggleAll(kind: 'skill' | 'mcp', on: boolean): void {
  const list = kind === 'skill' ? props.skillOptions : props.mcpOptions
  const next = on ? list.map((o) => o.key) : []
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

/** MCP 的"它是什么"：stdin 命令或 HTTP 地址，一行说清。 */
function mcpTarget(option: CapabilityOption): string {
  if (option.transport === 'http') return option.url ?? 'http'
  return [option.command, ...(option.args ?? [])].filter(Boolean).join(' ')
}

function goSettings(): void {
  void router.push('/settings?section=skills')
}
</script>

<template>
  <div class="cap-view">
    <div class="cap-page">
      <header class="cap-head">
        <div class="cap-head-text">
          <h1>本会话能力</h1>
          <p>
            开关只影响当前会话，下一轮对话立即生效；设置页的默认值不会被改动。
          </p>
        </div>
        <button type="button" class="icon-btn" title="重新读取 Skill / MCP 列表" @click="emit('refresh')">
          <el-icon :class="{ spin: loading }"><Refresh /></el-icon>
        </button>
      </header>

      <div class="cap-summary">
        <span class="sum-item">
          <el-icon><MagicStick /></el-icon>
          Skill <b class="mono">{{ ready ? `${skillOn}/${skillOptions.length}` : '…' }}</b>
        </span>
        <span class="sum-item">
          <el-icon><Connection /></el-icon>
          MCP <b class="mono">{{ ready ? `${mcpOn}/${mcpOptions.length}` : '…' }}</b>
        </span>
        <span class="sum-note">
          已启用 {{ ready ? skillOn + mcpOn : '…' }} 项
          <template v-if="noisy">· 偏多，首字延迟会变长</template>
        </span>
      </div>

      <p v-if="ready && noisy" class="cap-warn">
        <el-icon><Warning /></el-icon>
        启用越多，系统提示词越长、首字延迟越高。只保留这轮真正需要的就好。
      </p>

      <div v-if="ready && !total" class="cap-empty">
        <p>还没有可用的能力。</p>
        <button type="button" class="link" @click="goSettings">
          <el-icon><Setting /></el-icon>去设置页添加 Skill / MCP
        </button>
      </div>

      <template v-else>
        <!-- Skill：每个目录一份说明，按需加载 -->
        <section v-if="skillOptions.length" class="cap-card">
          <header class="card-head">
            <span class="card-icon skill"><el-icon><MagicStick /></el-icon></span>
            <span class="card-title">
              <b>Skill</b>
              <em>按需读取的知识包，命中相关任务时才会展开</em>
            </span>
            <span class="card-actions">
              <span class="mono">{{ skillOn }}/{{ skillOptions.length }}</span>
              <button type="button" class="link" @click="toggleAll('skill', true)">全选</button>
              <button type="button" class="link" @click="toggleAll('skill', false)">清空</button>
            </span>
          </header>
          <ul class="cap-rows">
            <li v-for="option in skillOptions" :key="`skill-${option.key}`" class="cap-row" :class="{ on: isOn('skill', option.key) }">
              <span class="row-main">
                <span class="row-name">
                  {{ option.label }}
                  <span v-if="option.fileCount" class="row-tag mono">{{ option.fileCount }} 文件</span>
                </span>
                <span class="row-desc">{{ option.description || '（Skill 未写描述，模型只能靠名称判断用途）' }}</span>
              </span>
              <el-switch
                :model-value="isOn('skill', option.key)"
                size="small"
                :aria-label="`本次对话启用 ${option.label}`"
                @change="(v: string | number | boolean) => toggle('skill', option.key, Boolean(v))"
              />
            </li>
          </ul>
        </section>

        <!-- MCP：外部工具服务，启用后会多出一批工具 -->
        <section v-if="mcpOptions.length" class="cap-card">
          <header class="card-head">
            <span class="card-icon mcp"><el-icon><Connection /></el-icon></span>
            <span class="card-title">
              <b>MCP</b>
              <em>外部工具服务，启用后工具会出现在本轮可调用列表里</em>
            </span>
            <span class="card-actions">
              <span class="mono">{{ mcpOn }}/{{ mcpOptions.length }}</span>
              <button type="button" class="link" @click="toggleAll('mcp', true)">全选</button>
              <button type="button" class="link" @click="toggleAll('mcp', false)">清空</button>
            </span>
          </header>
          <ul class="cap-rows">
            <li v-for="option in mcpOptions" :key="`mcp-${option.key}`" class="cap-row" :class="{ on: isOn('mcp', option.key) }">
              <span class="row-main">
                <span class="row-name">
                  {{ option.label }}
                  <span class="row-tag mono">{{ option.transport === 'http' ? 'HTTP' : 'stdio' }}</span>
                </span>
                <span class="row-desc" :title="option.description">{{ mcpTarget(option) || option.description }}</span>
              </span>
              <el-switch
                :model-value="isOn('mcp', option.key)"
                size="small"
                :aria-label="`本次对话启用 ${option.label}`"
                @change="(v: string | number | boolean) => toggle('mcp', option.key, Boolean(v))"
              />
            </li>
          </ul>
        </section>
      </template>

      <p class="cap-foot">
        想改默认值或新增能力，去
        <button type="button" class="link" @click="goSettings">设置 · Skill / MCP</button>
        ；这里的选择按会话单独保存。
      </p>
    </div>
  </div>
</template>

<style scoped>
.cap-view {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  background: var(--surface-sunken);
}

.cap-page {
  width: min(100%, 48rem);
  padding: var(--sp-6) var(--page-gutter) var(--sp-10);
  margin: 0 auto;
}

/* ---- 页头 ---- */
.cap-head {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}

.cap-head-text h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
}

.cap-head-text p {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-muted);
}

.cap-head .icon-btn {
  margin-left: auto;
}

.spin {
  animation: cap-spin 1s linear infinite;
}

@keyframes cap-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---- 计数条 ---- */
.cap-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2) var(--sp-4);
  padding: 8px 12px;
  margin-bottom: var(--sp-4);
  font-size: 12px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

.sum-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-soft);
}

.sum-item b {
  font-weight: 600;
  color: var(--ink);
}

.sum-note {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--ink-faint);
}

.cap-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 12px;
  margin: 0 0 var(--sp-4);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
  background: var(--warn-faint, var(--surface-raised));
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

/* ---- 能力卡 ---- */
.cap-card {
  margin-bottom: var(--sp-4);
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 14px;
  border-radius: var(--radius-sm);
}

.card-icon.skill {
  color: var(--accent-ink);
  background: var(--accent-soft);
}

.card-icon.mcp {
  color: var(--ink-soft);
  background: var(--surface-hover);
}

.card-title {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.card-title b {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.card-title em {
  font-size: 11.5px;
  font-style: normal;
  color: var(--ink-faint);
}

.card-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--ink-faint);
}

.link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}

.link:hover {
  text-decoration: underline;
}

.cap-rows {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.cap-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-top: 1px solid var(--line-soft);
}

.cap-row:first-child {
  border-top: 0;
}

.cap-row:hover {
  background: var(--surface-hover);
}

.row-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-soft);
}

.cap-row.on .row-name {
  color: var(--ink);
}

.row-tag {
  padding: 1px 5px;
  font-size: 10px;
  font-weight: 400;
  color: var(--ink-faint);
  background: var(--surface-hover);
  border-radius: 4px;
}

.row-desc {
  overflow: hidden;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cap-foot {
  margin: var(--sp-4) 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

/* ---- 空态 ---- */
.cap-empty {
  padding: 32px 16px;
  text-align: center;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}

.cap-empty p {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ink-muted);
}

@media (max-width: 640px) {
  .cap-page {
    padding: var(--sp-4) var(--page-gutter-tight) var(--sp-8);
  }

  .sum-note {
    margin-left: 0;
  }

  .row-desc {
    white-space: normal;
  }
}
</style>
