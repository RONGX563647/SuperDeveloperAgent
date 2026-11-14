import { createRouter, createWebHistory } from 'vue-router'
import { getToken, me, setUnauthorizedHandler } from './api/auth'
import { appStore } from './stores/app'

// 路由级懒加载：首屏只下载当前页面（对话/流水线/设置各自一个 chunk）
const ChatView = () => import('./views/ChatView.vue')
const WorkspaceHomeView = () => import('./views/WorkspaceHomeView.vue')
const PipelineView = () => import('./views/PipelineView.vue')
const LoginView = () => import('./views/LoginView.vue')
const ProjectIntakeView = () => import('./views/ProjectIntakeView.vue')
const ProjectListView = () => import('./views/ProjectListView.vue')
const SettingsView = () => import('./views/SettingsView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView },
    // 点击侧栏工作区标题进入：该工作区的调度 Agent + 全部会话
    { path: '/workspace/:projectId', name: 'workspace', component: WorkspaceHomeView },
    { path: '/pipeline', name: 'pipeline', component: PipelineView },
    { path: '/projects', name: 'project-list', component: ProjectListView },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/projects/intake', name: 'project-intake', component: ProjectIntakeView },
    // 旧详情页已并入工作台「诊断」抽屉；保留旧链接跳转，避免书签失效
    { path: '/projects/:id', redirect: '/projects' }
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
    appStore.clearIdentity()
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!appStore.identity.userId) {
    try {
      const identity = await me()
      appStore.setIdentity({
        userId: identity.userId,
        displayName: identity.displayName,
        role: identity.role,
        permissions: identity.permissions
      })
    } catch {
      appStore.clearIdentity()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

/**
 * 任意接口返回 401（会话过期 / 后端重启）时统一收口：
 * 清身份 + 回到登录页，并带上当前路径以便登录后跳回。
 *
 * <p>否则界面会一直是"已登录"的样子，用户点任何按钮都只看到
 * "未登录或会话已过期"，却始终停在原页面。
 */
setUnauthorizedHandler(() => {
  appStore.clearIdentity()
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

export default router
