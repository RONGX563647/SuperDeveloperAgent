<script setup lang="ts">
/**
 * 主区顶部 Header。
 *
 * 结构：左侧（窄屏菜单按钮 + 标题 + 副标题 + 全局上下文）／右侧操作区。
 * 高度固定 var(--header-height)，与页面内容共用 --page-gutter 左右留白，
 * 因此标题始终和下方卡片左边缘对齐。
 *
 * 视觉上不铺底色、不画分隔线：分层交给内容区自身（白纸画布或卡片）。
 */
import { Menu } from '@element-plus/icons-vue'
import { openNav } from '../navDrawer'
import ContextSwitcher from './ContextSwitcher.vue'

defineProps<{
  title: string
  subtitle?: string
  /** 显示全局项目/环境切换（对话页、流水线页用，设置页不需要）。 */
  context?: boolean
  /** context=true 时是否同时显示环境切换。 */
  contextEnv?: boolean
}>()
</script>

<template>
  <header class="page-header">
    <div class="head-left">
      <!-- 窄屏导航：侧栏 900px 以下变为抽屉，这里负责打开它 -->
      <button
        type="button"
        class="nav-switch"
        title="打开导航"
        aria-label="打开导航"
        @click="openNav"
      >
        <el-icon><Menu /></el-icon>
      </button>
      <h1 class="head-title">
        {{ title }}
      </h1>
      <span v-if="subtitle" class="head-subtitle">{{ subtitle }}</span>
      <div v-if="context" class="head-ctx">
        <ContextSwitcher :env="contextEnv !== false" />
      </div>
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
  gap: var(--sp-8);
  height: var(--header-height);
  padding: 0 var(--page-gutter);
}

.head-left {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  gap: var(--sp-5);
  min-width: 0;
}

.head-title {
  margin: 0;
  overflow: hidden;
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.015em;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 副标题是标题后的轻量元信息，用竖点分隔而不是胶囊 */
.head-subtitle {
  overflow: hidden;
  font-size: var(--text-xs);
  color: var(--ink-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.head-subtitle::before {
  padding-right: var(--sp-5);
  color: var(--ink-faint);
  content: '·';
}

.head-right {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--sp-3);
}

/* 全局上下文（项目/环境）：标题后方一行，不另起一块区域 */
.head-ctx {
  display: flex;
  flex: none;
  align-items: center;
  min-width: 0;
  padding-left: var(--sp-2);
  margin-left: var(--sp-2);
  border-left: 1px solid var(--line-soft);
}

/* 窄屏导航按钮：默认隐藏，900px 以下才出现 */
.nav-switch {
  display: none;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--control-md);
  height: var(--control-md);
  padding: 0;
  font-size: 16px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
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
    gap: var(--sp-4);
    padding: 0 var(--page-gutter-tight);
  }
  .head-left {
    gap: var(--sp-4);
  }
}

/* 手机：副标题让位给标题与操作按钮，避免顶栏横向溢出 */
@media (max-width: 600px) {
  .head-subtitle {
    display: none;
  }
  .head-right {
    gap: var(--sp-2);
  }
}
</style>
