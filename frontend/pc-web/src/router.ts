import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { watch } from 'vue'
import { authRedirect, useAuthStore } from '@quest/shared'

export function createAppRouter(history: RouterHistory, pinia?: Pinia) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', component: () => import('./views/NotFound.vue') },
      {
        path: '/practice',
        component: () => import('./layouts/PracticeLayout.vue'),
        children: [
          {
            path: '',
            component: () => import('./views/PracticeView.vue'),
            meta: { title: '学习工作区' },
          },
          {
            path: 'agent',
            component: () => import('./views/AgentView.vue'),
            meta: { title: '个人 Agent' },
          },
        ],
      },
      {
        path: '/admin',
        component: () => import('./layouts/AdminLayout.vue'),
        children: [
          { path: '', redirect: '/admin/users' },
          {
            path: 'users',
            component: () => import('./views/AdminUsersView.vue'),
            meta: { title: '用户管理' },
          },
        ],
      },
      { path: '/login', component: () => import('./views/LoginView.vue'), meta: { title: '登录' } },
      {
        path: '/change-password',
        component: () => import('./views/ChangePasswordView.vue'),
        meta: { title: '修改密码' },
      },
      {
        path: '/dev/connection',
        component: () => import('./views/ConnectionView.vue'),
        meta: { title: '连接诊断' },
      },
      {
        path: '/:pathMatch(.*)*',
        component: () => import('./views/NotFound.vue'),
        meta: { title: '页面不存在' },
      },
    ],
  })
  router.beforeEach(async (to) => {
    const auth = useAuthStore(pinia)
    await auth.restoreSession()
    return authRedirect(auth.user, to.path) ?? true
  })
  router.afterEach((to) => {
    document.title = `${String(to.meta.title ?? '工作台')} · 知序`
  })
  // 到期、401与服务端强制改密立即同步到当前页面，不等下一次点击路由。
  router.isReady().then(() => {
    const auth = useAuthStore(pinia)
    watch(
      () => [auth.authenticated, auth.user?.mustChangePassword, auth.user?.role],
      () => {
        if (router.currentRoute.value.path === '/login') return
        const redirect = authRedirect(auth.user, router.currentRoute.value.path)
        if (redirect) void router.replace(redirect)
      },
    )
  })
  return router
}
export const router = createAppRouter(createWebHistory(import.meta.env.BASE_URL))
