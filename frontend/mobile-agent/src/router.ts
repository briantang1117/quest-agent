import { createRouter, createWebHistory } from 'vue-router'
export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/sessions' },
    { path: '/sessions', component: () => import('./views/SessionsView.vue'), meta: { title: '学习助手' } },
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { title: '连接与登录' } },
    { path: '/chat/:id', component: () => import('./views/ChatView.vue'), meta: { title: 'Agent 对话' } },
    { path: '/:pathMatch(.*)*', component: () => import('./views/NotFound.vue'), meta: { title: '页面不存在' } },
  ],
})
router.afterEach(to => { document.title = `${String(to.meta.title ?? '学习助手')} · 知序` })
