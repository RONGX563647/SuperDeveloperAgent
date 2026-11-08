<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from './layouts/AppLayout.vue'

/**
 * 根组件：按路由决定外壳。
 *
 * <p>公开页（meta.public，如 /login）全屏渲染、<b>不套登录态外壳</b>——
 * 未登录用户看到的只有登录表单本身；其余页面走 AppLayout（侧栏 + 主区）。
 * 这是"未登录看不到控制台"的结构保证，而不只是守卫跳转。
 */
const route = useRoute()
const isPublic = computed(() => route.meta.public === true)
</script>

<template>
  <AppLayout v-if="!isPublic" />
  <router-view v-else />
</template>
