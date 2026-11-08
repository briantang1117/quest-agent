import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore, type UserProfile } from '@quest/shared'
import { createAppRouter } from '../router'
import LoginView from '../views/LoginView.vue'
import ChangePasswordView from '../views/ChangePasswordView.vue'
import AdminUsersView from '../views/AdminUsersView.vue'
import App from '../App.vue'
import { initializeErrorFeedback } from '../feedback'
import { readFile } from 'node:fs/promises'
let cleanupFeedback: (() => void) | undefined
import { resolve } from 'node:path'
const student: UserProfile = {
  id: '9007199254740993',
  username: 'student_a',
  displayName: '学员甲',
  role: 'student',
  status: 'active',
  mustChangePassword: true,
  createdAt: '2026-11-07T00:00:00Z',
  lastLoginAt: null,
}
let profile = { ...student }
let requests: InternalAxiosRequestConfig[] = []
let failureCode = 0
let wrappers: VueWrapper[] = []
const adapter = axios.defaults.adapter
const tokenResult = () => ({
  accessToken: 'test-token',
  expiresAt: new Date(Date.now() + 7200000).toISOString(),
  user: profile,
})
async function render(component: typeof LoginView, path: string, authenticated = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.configure('/gateway')
  if (authenticated) await auth.login({ username: profile.username, password: 'password1234' })
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    attachTo: document.body,
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: component !== App } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, auth, router }
}
async function fill(wrapper: VueWrapper, id: string, value: string) {
  await wrapper.get(`input#${id}`).setValue(value)
}
beforeEach(() => {
  cleanupFeedback = initializeErrorFeedback()
  profile = { ...student }
  requests = []
  failureCode = 0
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  axios.defaults.adapter = async (config) => {
    requests.push(config)
    const data =
      config.url?.endsWith('/login') || config.url?.endsWith('/change-password')
        ? tokenResult()
        : config.url?.endsWith('/auth/me')
          ? profile
          : config.method === 'get'
            ? { list: [student], total: 1, page: config.params.page, size: config.params.size }
            : student
    return {
      config,
      status: failureCode === 40100 ? 401 : 200,
      statusText: 'OK',
      headers: {},
      data: {
        code: failureCode,
        message: failureCode ? '请求被拒绝' : 'ok',
        data,
        traceId: 'trace-page',
      },
    }
  }
})
afterEach(() => {
  cleanupFeedback?.()
  for (const wrapper of wrappers) wrapper.unmount()
  wrappers = []
  useAuthStore().clearSession()
  Message.clear()
  axios.defaults.adapter = adapter
  vi.unstubAllGlobals()
})
describe('PC真实认证页面', () => {
  it('登录校验并提交真实凭据，首次改密不进入业务页', async () => {
    const { wrapper, router } = await render(LoginView, '/login?next=/admin/users')
    expect(wrapper.find('.brand-mark').exists()).toBe(false)
    expect(wrapper.find('.login-page').exists()).toBe(true)
    expect(wrapper.get('#login-title .brand-wordmark').text()).toBe('知序')
    expect(wrapper.text()).not.toContain('管理员只管理账号')
    expect(wrapper.text()).not.toContain('账号由管理员提供')
    expect(wrapper.text()).not.toContain('UTF-8')
    expect(wrapper.text()).not.toContain('12–64')
    await fill(wrapper, 'username', 'student_a')
    await fill(wrapper, 'password', '')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(requests).toHaveLength(0)
    expect(wrapper.text()).toContain('请输入密码')
    await fill(wrapper, 'username', 'student_a')
    await fill(wrapper, 'password', ' password1234 ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(JSON.parse(requests[0]!.data).password).toBe(' password1234 ')
    expect(router.currentRoute.value.path).toBe('/change-password')
  })
  it('登录不按新密码规则拦截已有凭据，也不trim密码', async () => {
    const { wrapper } = await render(LoginView, '/login')
    await fill(wrapper, 'username', 'student_a')
    await fill(wrapper, 'password', ' short ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(requests).toHaveLength(1)
    expect(JSON.parse(requests[0]!.data).password).toBe(' short ')
    expect(wrapper.text()).not.toContain('12–64')
  })
  it.each([
    [40100, '用户名或密码不正确'],
    [42900, '登录尝试过于频繁，请稍后再试'],
    [50300, '登录服务暂时不可用，请稍后重试'],
  ])('登录失败%s显示友好提示，不展示内部错误或traceId', async (code, message) => {
    const { wrapper } = await render(LoginView, '/login')
    failureCode = code as number
    await fill(wrapper, 'username', 'student_a')
    await fill(wrapper, 'password', 'password1234')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() =>
      expect(document.querySelector('.arco-message-content')?.textContent).toBe(message),
    )
    expect(wrapper.find('.auth-panel [role="alert"]').exists()).toBe(false)
    expect(document.querySelectorAll('.arco-message')).toHaveLength(1)
    expect(document.querySelector('.arco-message')?.textContent).not.toContain('trace-page')
    expect(wrapper.text()).not.toContain('trace-page')
    expect(wrapper.text()).not.toContain('请求被拒绝')
  })
  it('登录页不显示会话过期条，主动退出后保持干净表单', async () => {
    const { wrapper, auth } = await render(LoginView, '/login')
    auth.sessionNotice = '登录已过期，请重新登录'
    await flushPromises()
    expect(wrapper.text()).not.toContain('登录已过期')
    expect(wrapper.find('.auth-panel .arco-alert').exists()).toBe(false)
  })
  it('改密页使用登录页纸张布局和纯文字品牌，不显示内部会话说明', async () => {
    const { wrapper } = await render(App, '/change-password', true)
    expect(wrapper.find('.password-page.login-page').exists()).toBe(true)
    expect(wrapper.find('.login-header .brand-mark').exists()).toBe(false)
    expect(wrapper.find('.login-header .brand-sub').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('改密会撤销旧会话')
  })
  it.each([
    [40100, '当前密码不正确或登录已失效，请重新确认'],
    [40000, '请检查新密码要求，新密码不能与当前密码相同'],
    [50300, '修改密码失败，请稍后重试'],
  ])('改密失败%s保留友好中文且只提示一次', async (code, message) => {
    const { wrapper } = await render(ChangePasswordView, '/change-password', true)
    failureCode = code as number
    await fill(wrapper, 'current-password', 'password1234')
    await fill(wrapper, 'new-password', 'newpassword123')
    await fill(wrapper, 'confirm-password', 'newpassword123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(document.querySelectorAll('.arco-message')).toHaveLength(1)
    expect(document.querySelector('.arco-message-content')?.textContent).toBe(message)
    expect(document.body.textContent).not.toContain('trace-page')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
  it('改密验证UTF8和重复密码，成功后按身份分流', async () => {
    const { wrapper, router } = await render(ChangePasswordView, '/change-password', true)
    await fill(wrapper, 'current-password', 'password1234')
    await fill(wrapper, 'new-password', '密'.repeat(25))
    await fill(wrapper, 'confirm-password', '密'.repeat(25))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(requests).toHaveLength(1)
    await fill(wrapper, 'new-password', 'newpassword123')
    await fill(wrapper, 'confirm-password', 'different1234')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(requests).toHaveLength(1)
    profile = { ...student, mustChangePassword: false }
    await fill(wrapper, 'confirm-password', 'newpassword123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(
      requests.map((request) => request.url),
      wrapper.text(),
    ).toHaveLength(2)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/practice'))
  })
})
describe('用户管理页面', () => {
  it('后台表格容器裁剪超出圆角的内容', async () => {
    setActivePinia(createPinia())
    const style = document.createElement('style')
    style.textContent = await readFile(resolve('src/layouts/admin-layout.css'), 'utf8')
    const card = document.createElement('div')
    card.className = 'admin-users-card'
    const container = document.createElement('div')
    container.className = 'arco-table-container'
    card.append(container)
    document.head.append(style)
    document.body.append(card)
    try {
      expect(getComputedStyle(container).overflow).toBe('hidden')
    } finally {
      card.remove()
      style.remove()
    }
  })
  it('筛选区域在Arco表单内部用独立div布局，不继承表单纵向排列', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    const area = wrapper.get<HTMLElement>('form[data-test="filter"] > .user-filter-area')
    expect(area.element.tagName).toBe('DIV')
    expect(area.find('.user-filter-form').exists()).toBe(true)
    expect(area.find('.user-filter-actions').exists()).toBe(true)
    expect(area.classes()).not.toContain('arco-form')
  })
  it('筛选由单个Arco表单承载并通过提交查询', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    const form = wrapper.get('form[data-test="filter"]')
    expect(form.classes()).toContain('arco-form')
    expect(wrapper.findAll('form')).toHaveLength(1)
    expect(form.find('button[type="submit"]').exists()).toBe(true)
    await fill(wrapper, 'user-keyword', 'student')
    await form.trigger('submit')
    await flushPromises()
    expect(requests.at(-1)?.params).toMatchObject({ keyword: 'student', page: 1 })
  })
  it('表格横向宽度跟随容器，不写死为1000px', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    const table = wrapper.get<HTMLTableElement>('.arco-table-element')
    expect(table.element.style.width).toBe('100%')
  })
  it('加载真实列表与分页筛选，创建、启停、重置均调用契约', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    expect(wrapper.text()).toContain('学员甲')
    expect(wrapper.text()).not.toContain('管理普通用户的访问资格')
    expect(wrapper.text()).not.toContain('只列出普通用户账号')
    expect(wrapper.text()).not.toContain('不展示私有题库')
    expect(wrapper.text()).not.toContain('使用量统计尚未接入')
    expect(wrapper.find('[data-test="reset-filters"]').exists()).toBe(true)
    expect(wrapper.find('.user-toolbar [data-test="create-user"]').exists()).toBe(true)
    await fill(wrapper, 'user-keyword', 'student')
    await wrapper.get('[data-test="filter"]').trigger('submit')
    await flushPromises()
    expect(requests.at(-1)?.params).toMatchObject({ keyword: 'student', page: 1 })
    await wrapper.get('[data-test="create-user"]').trigger('click')
    await flushPromises()
    await fill(wrapper, 'create-username', 'new_student')
    await fill(wrapper, 'create-display-name', ' 新学员 ')
    await fill(wrapper, 'initial-password', 'password1234')
    await wrapper.get('[data-test="user-form"]').trigger('submit')
    await flushPromises()
    const created = requests.find((r) => r.method === 'post' && r.url === '/api/v1/admin/users')
    expect(JSON.parse(created!.data)).toMatchObject({
      username: 'new_student',
      displayName: '新学员',
    })
    await wrapper.get('[data-test="toggle-status"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="confirm-action"]').trigger('click')
    await flushPromises()
    expect(
      requests.some((r) => r.method === 'patch' && r.url?.endsWith(`${student.id}/status`)),
    ).toBe(true)
    await wrapper.get('[data-test="reset-password"]').trigger('click')
    await flushPromises()
    await fill(wrapper, 'reset-password', 'newpassword123')
    await wrapper.get('[data-test="user-form"]').trigger('submit')
    await flushPromises()
    expect(requests.some((r) => r.url?.endsWith(`${student.id}/reset-password`))).toBe(true)
    const count = requests.length
    await new Promise((r) => setTimeout(r, 30))
    expect(requests.length).toBe(count)
  })
  it('查询清空回第一页，弹窗底部提交先校验且失败不关闭', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    await fill(wrapper, 'user-keyword', 'student')
    await wrapper.get('[data-test="filter"]').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-test="reset-filters"]').trigger('click')
    await flushPromises()
    expect(requests.at(-1)?.params).toMatchObject({ page: 1, keyword: '', status: '' })
    await wrapper.get('[data-test="create-user"]').trigger('click')
    await flushPromises()
    const before = requests.length
    await wrapper.get('.arco-modal-footer [data-test="confirm-action"]').trigger('click')
    await flushPromises()
    expect(requests.length).toBe(before)
    expect(wrapper.find('[data-test="user-form"]').exists()).toBe(true)
    await fill(wrapper, 'create-username', 'new_student')
    await fill(wrapper, 'create-display-name', '新用户')
    await fill(wrapper, 'initial-password', 'password1234')
    failureCode = 40900
    await wrapper.get('.arco-modal-footer [data-test="confirm-action"]').trigger('click')
    await flushPromises()
    expect(requests.length).toBe(before + 1)
    expect(wrapper.find('[data-test="user-form"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('trace-page')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(document.querySelectorAll('.arco-message')).toHaveLength(1)
  })
  it('分页、状态筛选和页大小来自接口，不触发轮询', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    wrapper.getComponent({ name: 'Pagination' }).vm.$emit('change', 2)
    await flushPromises()
    expect(requests.at(-1)?.params.page).toBe(2)
    wrapper.getComponent({ name: 'Pagination' }).vm.$emit('pageSizeChange', 50)
    await flushPromises()
    expect(requests.at(-1)?.params).toMatchObject({ page: 1, size: 50 })
    wrapper.getComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'disabled')
    await wrapper.get('[data-test="filter"]').trigger('submit')
    await flushPromises()
    expect(requests.at(-1)?.params.status).toBe('disabled')
  })
  it('创建进行中防重复提交；失败仅全局一次且保留弹窗、不展示traceId', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    await wrapper.get('[data-test="create-user"]').trigger('click')
    await flushPromises()
    await fill(wrapper, 'create-username', 'new_student')
    await fill(wrapper, 'create-display-name', '新学员')
    await fill(wrapper, 'initial-password', 'password1234')
    let resolve!: (value: AxiosResponse) => void
    let count = 0
    axios.defaults.adapter = async (config) => {
      count++
      return new Promise((done) => {
        resolve = done as typeof resolve
        requests.push(config)
      })
    }
    await wrapper.get('[data-test="user-form"]').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-test="user-form"]').trigger('submit')
    await flushPromises()
    expect(count).toBe(1)
    resolve({
      config: requests.at(-1)!,
      status: 409,
      statusText: 'Conflict',
      headers: {},
      data: { code: 40900, message: '用户名已存在', data: null, traceId: 'conflict-trace' },
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('conflict-trace')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(document.querySelectorAll('.arco-message')).toHaveLength(1)
    expect(document.querySelector('.arco-message-content')?.textContent).toBe(
      '用户名已存在，请更换后重试',
    )
    expect(wrapper.find('[data-test="user-form"]').exists()).toBe(true)
  })
  it('列表失败不伪造0或成功空表，可手动刷新', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(AdminUsersView, '/admin/users', true)
    failureCode = 50300
    await wrapper.get('[data-test="refresh-users"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('trace-page')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(document.querySelectorAll('.arco-message')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('共 0')
  })
})
describe('实际路由和导航', () => {
  it('登录页外壳只显示纯文字品牌，不影响next跳转路径', async () => {
    const { wrapper, router } = await render(App, '/login?next=/admin/users')
    expect(wrapper.find('.login-header .brand-mark').exists()).toBe(false)
    expect(wrapper.get('.login-header .brand').text()).toBe('知序')
    expect(wrapper.find('.login-header .brand-sub').exists()).toBe(false)
    expect(router.currentRoute.value.query.next).toBe('/admin/users')
  })
  it('刷新新store后先向me核验身份再进入原管理页，不相信缓存角色', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    localStorage.setItem(
      'quest.auth.session',
      JSON.stringify({
        baseURL: '/gateway',
        accessToken: 'saved-token',
        expiresAt: new Date(Date.now() + 3600000).toISOString(),
        user: { role: 'student' },
      }),
    )
    const { auth, router } = await render(App, '/admin/users')
    expect(requests[0]?.url).toBe('/api/v1/auth/me')
    expect(auth.user?.role).toBe('admin')
    expect(router.currentRoute.value.path).toBe('/admin/users')
  })
  it('student不能进入管理路由；匿名不能靠诊断Token获得身份', async () => {
    profile = { ...student, mustChangePassword: false }
    const { wrapper, router, auth } = await render(App, '/admin/users', true)
    expect(router.currentRoute.value.path).toBe('/practice')
    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(false)
    await router.push('/practice/agent')
    expect(router.currentRoute.value.path).toBe('/practice/agent')
    auth.clearSession()
    await flushPromises()
    await router.push('/dev/connection')
    expect(router.currentRoute.value.path).toBe('/login')
  })
  it('Admin使用独立全宽后台，一级navbar和侧栏分层且支持折叠', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper } = await render(App, '/admin/users', true)
    expect(wrapper.find('.admin-shell').exists()).toBe(true)
    expect(wrapper.find('.admin-navbar [aria-label="一级导航"]').text()).toContain('系统管理')
    expect(wrapper.find('.admin-sidebar').text()).toContain('用户管理')
    expect(wrapper.find('.topbar').exists()).toBe(false)
    expect(wrapper.find('.site-footer').exists()).toBe(false)
    expect(wrapper.find('.admin-footer').exists()).toBe(true)
    expect(wrapper.find('.admin-navbar .arco-menu-item .arco-menu-icon').exists()).toBe(false)
    expect(wrapper.find('.admin-sidebar .arco-menu-item .arco-icon').exists()).toBe(false)
    expect(wrapper.find('[data-test="account-menu-trigger"] .arco-avatar').exists()).toBe(true)
    expect(wrapper.find('.admin-account > a').exists()).toBe(false)
    expect(wrapper.find('.sidebar-note').exists()).toBe(false)
    expect(wrapper.find('.admin-brand .brand-mark').exists()).toBe(false)
    expect(wrapper.get('.admin-brand-name').text()).toBe('知序')
    expect(wrapper.get('.admin-brand-label').text()).toBe('管理后台')
    expect(wrapper.find('[data-test="collapse-menu"] .arco-icon-menu-fold').exists()).toBe(true)
    expect(wrapper.get('[data-test="collapse-menu"]').text()).not.toContain('收起菜单')
    await wrapper.get('[data-test="collapse-menu"]').trigger('click')
    expect(wrapper.find('.admin-shell').classes()).toContain('is-collapsed')
    expect(wrapper.find('[data-test="collapse-menu"] .arco-icon-menu-unfold').exists()).toBe(true)
  })
  it('初始窄屏菜单真正折叠，手动展开与缩放始终同步组件状态', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    window.innerWidth = 560
    const { wrapper } = await render(App, '/admin/users', true)
    const sidebarMenu = () => wrapper.get('.admin-sidebar').findComponent({ name: 'BaseMenu' })
    expect(wrapper.get('.admin-shell').classes()).toContain('is-collapsed')
    expect(sidebarMenu().props('collapsed')).toBe(true)
    expect(wrapper.get('[data-test="collapse-menu"]').attributes('aria-expanded')).toBe('false')
    await wrapper.get('[data-test="collapse-menu"]').trigger('click')
    expect(wrapper.get('.admin-shell').classes()).not.toContain('is-collapsed')
    expect(sidebarMenu().props('collapsed')).toBe(false)
    window.innerWidth = 1440
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    window.innerWidth = 560
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(sidebarMenu().props('collapsed')).toBe(true)
    expect(wrapper.get('.admin-shell').classes()).toContain('is-collapsed')
    expect(wrapper.find('[data-test="collapse-menu"] .arco-icon-menu-unfold').exists()).toBe(true)
  })
  it('跨断点恢复桌面偏好，同一窄屏区间resize不覆盖手动选择，卸载移除监听', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const remove = vi.spyOn(window, 'removeEventListener')
    const { wrapper } = await render(App, '/admin/users', true)
    window.innerWidth = 560
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    await wrapper.get('[data-test="collapse-menu"]').trigger('click')
    window.innerWidth = 570
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.get('.admin-shell').classes()).not.toContain('is-collapsed')
    window.innerWidth = 1440
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.get('.admin-shell').classes()).not.toContain('is-collapsed')
    wrapper.unmount()
    wrappers = wrappers.filter((item) => item !== wrapper)
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function))
    remove.mockRestore()
  })
  it('Admin没有学习和Agent入口，直接路由也被隔离，登出清身份', async () => {
    profile = { ...student, role: 'admin', mustChangePassword: false }
    const { wrapper, router, auth } = await render(App, '/admin/users', true)
    expect(wrapper.find('a[href="/practice"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/practice/agent"]').exists()).toBe(false)
    await router.push('/admin/agent')
    expect(router.currentRoute.value.path).toBe('/admin/users')
    await router.push('/practice/agent')
    expect(router.currentRoute.value.path).toBe('/admin/users')
    await wrapper.get('[data-test="account-menu-trigger"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(document.querySelector('[data-test="logout"]')).not.toBeNull())
    ;(document.querySelector('[data-test="logout"]') as HTMLElement).click()
    await flushPromises()
    expect(auth.authenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/login')
  })
  it('强制改密不显示业务/诊断入口；过期自动跳login', async () => {
    const { wrapper, auth, router } = await render(App, '/practice', true)
    expect(router.currentRoute.value.path).toBe('/change-password')
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('a[href="/dev/connection"]').exists()).toBe(false)
    auth.clearSession('登录已过期，请重新登录')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
