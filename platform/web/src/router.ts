import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'
import PipelineView from './views/PipelineView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView },
    { path: '/pipeline', name: 'pipeline', component: PipelineView }
  ]
})

export default router
