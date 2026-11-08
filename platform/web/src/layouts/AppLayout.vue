<script setup lang="ts">
import { ref } from 'vue'
import AppAside from './components/AppAside.vue'

/**
 * 应用外壳（App Shell）。
 *
 * <p>骨架：横向 Flex = 侧栏（固定 260px，可折叠）+ 主区（flex-1，纵向 Header / 内容 / 输入）。
 * 高度锁死 100dvh，外层 overflow:hidden —— 整页永远没有滚动条，
 * 滚动只发生在侧栏列表与内容区内。
 *
 * <p>主区背景带一层极淡的顶部径向提亮（DSH 的"暗房打光"感），
 * 用 ::before 画在内容层之下，不拦截事件、不影响滚动容器。
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
  position: relative;
  display: flex;
  flex-direction: row;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--bg);
}
/* 浅灰底不需要氛围打光层：层级交给"浅灰底 + 纯白卡"的白度差。
   加任何渐变都会让干净的浅灰底变脏。 */
.app-main {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
</style>
