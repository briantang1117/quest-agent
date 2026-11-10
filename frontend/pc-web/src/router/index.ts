import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { watch } from 'vue'
import { authRedirect, useAuthStore } from '@quest/shared'
import { setupRouteListener } from '../utils/route-listener'

export function createAppRouter(history: RouterHistory, pinia?: Pinia) {
  const router = createRouter({
    history,
    scrollBehavior(_to, _from, savedPosition) {
      if (savedPosition) {
        return savedPosition
      }
      return { top: 0 }
    },
    routes: [
      { path: '/', component: () => import('../views/NotFound.vue') },
      {
        path: '/practice',
        component: () => import('../layouts/PracticeLayout.vue'),
        children: [
          {
            path: '',
            name: 'PracticeView',
            component: () => import('../views/PracticeView.vue'),
            meta: { title: '学习工作区', pageDeepth: 1 },
          },
          {
            path: 'syllabus',
            name: 'SyllabusGuideView',
            component: () => import('../views/SyllabusGuideView.vue'),
            meta: { title: '考纲导览', pageDeepth: 1 },
          },
          {
            path: 'materials',
            name: 'LearningMaterialsView',
            component: () => import('../views/LearningMaterialsView.vue'),
            meta: { title: '学习资料', pageDeepth: 1 },
          },
          {
            path: 'profile',
            name: 'ProfileView',
            component: () => import('../views/ProfileView.vue'),
            meta: { title: '个人中心', pageDeepth: 1 },
          },
          {
            path: 'agent',
            name: 'AgentView',
            component: () => import('../views/AgentView.vue'),
            meta: { title: '个人 Agent', pageDeepth: 1, transientQueryKeys: ['prompt'] },
          },
        ],
      },
      {
        path: '/admin',
        component: () => import('../layouts/AdminLayout.vue'),
        children: [
          { path: '', redirect: '/admin/users' },
          {
            path: 'banks',
            name: 'AdminBanksView',
            component: () => import('../views/AdminBanksView.vue'),
            meta: { title: '公共题库', pageDeepth: 1 },
          },
          {
            path: 'banks/:id',
            name: 'AdminBankDetailView',
            component: () => import('../views/AdminBankDetailView.vue'),
            meta: { title: '管理题库', pageDeepth: 2 },
          },
          {
            path: 'questions/:id',
            name: 'AdminQuestionView',
            component: () => import('../views/AdminQuestionView.vue'),
            meta: { title: '题目审核', pageDeepth: 3 },
          },
          {
            path: 'syllabi',
            name: 'AdminSyllabiView',
            component: () => import('../views/AdminSyllabiView.vue'),
            meta: { title: '考纲管理', pageDeepth: 1 },
          },
          {
            path: 'syllabi/:id',
            name: 'AdminSyllabusDetailView',
            component: () => import('../views/AdminSyllabusDetailView.vue'),
            meta: { title: '考纲详情', pageDeepth: 2 },
          },
          {
            path: 'users',
            name: 'AdminUsersView',
            component: () => import('../views/AdminUsersView.vue'),
            meta: { title: '用户管理', pageDeepth: 1 },
          },
          {
            path: 'logs',
            name: 'AdminAiLogsView',
            component: () => import('../views/AdminAiLogsView.vue'),
            meta: { title: '系统日志', pageDeepth: 1 },
          },
        ],
      },
      {
        path: '/login',
        component: () => import('../views/LoginView.vue'),
        meta: { title: '登录' },
      },
      {
        path: '/change-password',
        component: () => import('../views/ChangePasswordView.vue'),
        meta: { title: '修改密码' },
      },
      {
        path: '/dev/connection',
        component: () => import('../views/ConnectionView.vue'),
        meta: { title: '连接诊断' },
      },
      {
        path: '/:pathMatch(.*)*',
        component: () => import('../views/NotFound.vue'),
        meta: { title: '页面不存在' },
      },
    ],
  })
  setupRouteListener(router)
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
