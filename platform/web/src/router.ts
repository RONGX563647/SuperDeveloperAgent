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
    { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView, meta: { title: '对话' } },
    // 点击侧栏工作区标题进入：该工作区的调度 Agent + 全部会话
    { path: '/workspace/:projectId', name: 'workspace', component: WorkspaceHomeView, meta: { title: '工作区' } },
    { path: '/pipeline', name: 'pipeline', component: PipelineView, meta: { title: '流水线' } },
    { path: '/projects', name: 'project-list', component: ProjectListView, meta: { title: '项目' } },
    { path: '/settings', name: 'settings', component: SettingsView, meta: { title: '设置' } },
    { path: '/projects/intake', name: 'project-intake', component: ProjectIntakeView, meta: { title: '接入项目' } },
    // 旧详情页已并入工作台「诊断」抽屉；保留旧链接跳转，避免书签失效
    { path: '/projects/:id', redirect: '/projects' },
    // 未知路径回对话页：否则主区是一片空白，看起来像页面崩了
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

const APP_NAME = '研发运维智能体平台'

/** 标签页标题跟随页面：多开标签时能直接分辨是哪个页面。 */
router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · ${APP_NAME}` : APP_NAME
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
        permissions: identity.permissions,
        usingInitialPassword: identity.usingInitialPassword
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
