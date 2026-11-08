import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import axios, { type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore, type UserProfile } from '@quest/shared'
import { createAppRouter } from '../router'
import App from '../App.vue'

const student: UserProfile = {
  id: '2',
  username: 'alice',
  displayName: '学员甲',
  role: 'student',
  status: 'active',
  mustChangePassword: false,
  createdAt: '2026-11-08T00:00:00Z',
  lastLoginAt: null,
}
let wrappers: VueWrapper[] = []
const adapter = axios.defaults.adapter
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  axios.defaults.adapter = async (config) => {
    const data =
      config.url?.endsWith('/login') || config.url?.endsWith('/change-password')
        ? {
            accessToken: 'token',
            expiresAt: new Date(Date.now() + 7200000).toISOString(),
            user: student,
          }
        : config.url?.endsWith('/auth/me')
          ? student
          : config.url?.endsWith('/study-preference')
            ? { subject: null }
            : config.url?.includes('/coach/sessions')
              ? { list: [] }
              : { list: [], total: 0 }
    return {
      config: config as InternalAxiosRequestConfig,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: { code: 0, message: 'ok', data, traceId: 'trace' },
    }
  }
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers = []
  useAuthStore().clearSession()
  axios.defaults.adapter = adapter
  vi.unstubAllGlobals()
})
async function render(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.configure('/gateway')
  await auth.login({ username: student.username, password: 'password1234' })
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia, router, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, auth }
}

describe('学员端顶栏', () => {
  it('不再显示方形“序”标识，只保留文字品牌', async () => {
    const { wrapper } = await render('/practice')
    expect(wrapper.find('.brand-mark').exists()).toBe(false)
    expect(wrapper.get('.brand').text()).toContain('知序')
  })
  it.each([
    ['/practice', '学习工作区'],
    ['/practice/agent', '个人 Agent'],
    ['/practice/profile', '个人中心'],
  ])('%s 只高亮当前入口，学习工作区不长期激活', async (path, label) => {
    const { wrapper } = await render(path)
    const links = wrapper.findAll('.topbar nav a')
    const current = links.filter((link) => link.classes().includes('is-current'))
    expect(current).toHaveLength(1)
    expect(current[0]!.text()).toBe(label)
  })
  it('右上角合并为头像下拉，修改密码和退出登录收进菜单', async () => {
    const { wrapper } = await render('/practice')
    const header = wrapper.get('.topbar')
    expect(header.findAll('a').some((link) => link.text() === '修改密码')).toBe(false)
    expect(header.find('[data-test="logout"]').exists()).toBe(false)
    const trigger = header.get('[data-test="user-account-menu"]')
    expect(trigger.text()).toContain('学员甲')
    expect(trigger.text()).toContain('普通用户')
    await trigger.trigger('click')
    await vi.waitFor(() => expect(document.querySelector('[data-test="logout"]')).not.toBeNull())
    expect(document.body.textContent).toContain('修改密码')
  })
})
