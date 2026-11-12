<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppAside from './components/AppAside.vue'
import { closeNav, navDrawerOpen } from './navDrawer'

/**
 * 应用外壳（App Shell）。
 *
 * <p>骨架：横向 Flex = 侧栏（固定 260px，可折叠）+ 主区（flex-1）。
 * 高度锁死 100dvh，外层 overflow:hidden——整页永远没有滚动条。
 *
 * <p><b>移动端（≤900px）</b>：侧栏变为离屏抽屉，由 PageHeader 的菜单按钮打开；
 * 抽屉带遮罩，点击遮罩 / 路由跳转 / Esc 都会关闭，主区始终占满宽度。
 *
 * <p><b>本组件只用于「需要登录态」的页面</b>：
 * 路由 meta.public = true 的页面（如 /login）由 App.vue 直接全屏渲染，不经过这里，
 * 因此未登录时用户看到的只有登录页，绝不会带着侧栏"偷看"控制台。
 */
const route = useRoute()
const collapsed = ref(false)

/** 防御性断言：若误把公开页挂进外壳，开发期直接暴露问题。 */
const isPublicPage = computed(() => route.meta.public === true)

const MOBILE_QUERY = '(max-width: 900px)'
let media: MediaQueryList | null = null

function onViewportChange(): void {
  if (!media?.matches) closeNav()
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') closeNav()
}

// 手机上点导航跳页后，抽屉必须自动收起，不能盖住新页面
watch(() => route.fullPath, closeNav)

onMounted(() => {
  media = window.matchMedia(MOBILE_QUERY)
  media.addEventListener('change', onViewportChange)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', onViewportChange)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="app-shell" :class="{ 'aside-collapsed': collapsed }">
    <AppAside
      v-model:collapsed="collapsed"
      :mobile-open="navDrawerOpen"
      @close="closeNav"
    />
    <div
      v-if="navDrawerOpen"
      class="nav-scrim"
      aria-hidden="true"
      @click="closeNav"
    />
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

/* 移动端抽屉遮罩：只在抽屉打开时渲染 */
.nav-scrim {
  position: fixed;
  inset: 0;
  z-index: 70;
  background: rgb(15 20 28 / 38%);
  animation: scrim-in var(--dur-fast) ease;
}

@keyframes scrim-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
