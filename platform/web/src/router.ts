import { createRouter, createWebHistory } from 'vue-router'
import { getToken, me } from './api/auth'
import { appStore } from './stores/app'
import ChatView from './views/ChatView.vue'
import PipelineView from './views/PipelineView.vue'
import LoginView from './views/LoginView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView },
    { path: '/pipeline', name: 'pipeline', component: PipelineView }
  ]
})

/**
 * 登录守卫：
 * - 公开页（/login）直接放行；
 * - 无 token → 去 /login；
 * - 有 token 但身份未加载 → 调 /auth/me 校验（失效会清 token 并跳登录）。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  if (!getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!appStore.identity.userId) {
    try {
      const identity = await me()
      appStore.setIdentity({
        userId: identity.userId,
        displayName: identity.displayName,
        role: identity.role
      })
    } catch {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

export default router
