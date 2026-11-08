<script setup lang="ts">
import { ref } from 'vue'
import AppAside from './components/AppAside.vue'

/**
 * 应用外壳（App Shell）。
 *
 * <p>骨架：横向 Flex = 侧栏（固定 260px，可折叠）+ 主区（flex-1，纵向 Header / 内容 / 输入）。
 * 高度锁死 100dvh，外层 overflow:hidden——**整页永远没有滚动条**，
 * 滚动只发生在侧栏列表与内容区内。
 */
const collapsed = ref(false)
</script>

<template>
  <div class="app-shell" :class="{ 'aside-collapsed': collapsed }">
    <AppAside v-model:collapsed="collapsed" />
    <div class="app-main">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: row;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--bg);
}
.app-main {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
</style>
