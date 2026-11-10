<script setup lang="ts">
/**
 * 主区顶部 Header —— 对齐 DSH 的会话标题行：
 * 高度 52px、与页面同色（不另起一层白/灰底）、**不画底部分隔线**（DSH 靠留白分层，
 * 只有内容滚动到 Header 下方时才需要视觉边界，这里由内容区自身的 padding 承担）。
 *
 * <p>标题 14px/600；副标题与标题基线对齐、弱化到 --ink-muted；
 * 右侧操作区用 gap 8px 紧凑排布。
 *
 * <p>窄屏（≤900px）侧栏会整体收起，此时 Header 左侧才出现导航切换按钮 ——
 * 侧栏消失不等于导航消失。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Notebook } from '@element-plus/icons-vue'

defineProps<{ title: string; subtitle?: string }>()

const route = useRoute()
const router = useRouter()

/** 两个主页面之间互切：窄屏下这是唯一入口。 */
const switchTarget = computed(() =>
  route.name === 'chat'
    ? { to: '/pipeline', label: '流水线', icon: Notebook }
    : { to: '/chat', label: '对话', icon: ChatDotRound }
)
</script>

<template>
  <header class="page-header">
    <div class="head-left">
      <!-- 窄屏导航：侧栏 900px 以下收起，这里兜住可达性 -->
      <button
        type="button"
        class="nav-switch"
        :title="`切换到${switchTarget.label}`"
        @click="router.push(switchTarget.to)"
      >
        <el-icon><component :is="switchTarget.icon" /></el-icon>
      </button>
      <h1 class="head-title">
        {{ title }}
      </h1>
      <span v-if="subtitle" class="head-subtitle">{{ subtitle }}</span>
    </div>
    <div class="head-right">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: var(--header-height);
  padding: 0 20px;
  background: transparent;
}
.head-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.head-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
  white-space: nowrap;
}
/* DSH 的会话标题后面紧跟一串轻量元信息，用竖点分隔而不是胶囊 */
.head-subtitle {
  overflow: hidden;
  font-size: 12px;
  color: var(--ink-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.head-subtitle::before {
  padding-right: 10px;
  color: var(--ink-faint);
  content: '·';
}
.head-right {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

/* 窄屏导航按钮：默认隐藏，900px 以下才出现 */
.nav-switch {
  display: none;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-right: 2px;
  font-size: 15px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.nav-switch:hover {
  color: var(--ink);
  background: var(--surface-hover);
}

@media (max-width: 900px) {
  .nav-switch {
    display: flex;
  }
  .page-header {
    padding: 0 14px;
  }
}
</style>
