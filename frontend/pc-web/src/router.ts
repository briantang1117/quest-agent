import { createRouter, createWebHistory } from 'vue-router'
export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/practice' },
    {
      path: '/practice',
      component: () => import('./layouts/PracticeLayout.vue'),
      children: [
        {
          path: '',
          component: () => import('./views/PracticeView.vue'),
          meta: { title: '刷题空间' },
        },
      ],
    },
    {
      path: '/admin',
      component: () => import('./layouts/AdminLayout.vue'),
      children: [
        { path: '', component: () => import('./views/AdminView.vue'), meta: { title: '题库管理' } },
        {
          path: 'agent',
          component: () => import('./views/AgentView.vue'),
          meta: { title: '题库 Agent' },
        },
      ],
    },
    {
      path: '/login',
      component: () => import('./views/LoginView.vue'),
      meta: { title: '连接与登录' },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('./views/NotFound.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})
router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '工作台')} · 知序`
})
