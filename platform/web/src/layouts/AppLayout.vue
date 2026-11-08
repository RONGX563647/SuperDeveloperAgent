<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppAside from './components/AppAside.vue'

/**
 * 应用外壳（App Shell）。
 *
 * <p>骨架：横向 Flex = 侧栏（固定 260px，可折叠）+ 主区（flex-1）。
 * 高度锁死 100dvh，外层 overflow:hidden——整页永远没有滚动条。
 *
 * <p><b>本组件只用于「需要登录态」的页面</b>：
 * 路由 meta.public = true 的页面（如 /login）由 App.vue 直接全屏渲染，不经过这里，
 * 因此未登录时用户看到的只有登录页，绝不会带着侧栏"偷看"控制台。
 */
const route = useRoute()
const collapsed = ref(false)

/** 防御性断言：若误把公开页挂进外壳，开发期直接暴露问题。 */
const isPublicPage = computed(() => route.meta.public === true)
</script>

<template>
  <div class="app-shell" :class="{ 'aside-collapsed': collapsed }">
    <AppAside v-model:collapsed="collapsed" />
    <div class="app-main">
      <div v-if="isPublicPage" class="misplaced-warning">
        公开页不应挂载在登录态外壳内（检查路由 meta.public）
      </div>
      <router-view v-else />
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
.app-main {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.misplaced-warning {
  display: grid;
  flex: 1;
  place-items: center;
  font-size: 13px;
  color: #c45656;
}
</style>
