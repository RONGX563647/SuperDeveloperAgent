<script setup lang="ts">
/**
 * 主区顶部 Header：高度固定 60px，两端对齐，底部 1px 分隔。
 *
 * <p>放在主区内部（而不是整页通栏），与「侧栏通高 + 右侧独立 Header」的骨架一致。
 */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <header class="page-header">
    <div class="head-left">
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
  height: 60px;
  padding: 0 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}
.head-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.head-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  white-space: nowrap;
}
.head-subtitle {
  overflow: hidden;
  font-size: 12px;
  color: #a0a6ad;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.head-right {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}
</style>
