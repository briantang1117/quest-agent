import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { useAuthStore } from '@quest/shared'
import AdminLayout from '../layouts/AdminLayout.vue'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

it('切换一级导航后自动展开当前二级分组，同时保留手动收起能力', async () => {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 })
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    id: '1',
    username: 'admin',
    displayName: '管理员',
    role: 'admin',
    status: 'active',
    mustChangePassword: false,
    createdAt: '2026-11-08T00:00:00Z',
    lastLoginAt: null,
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/users', component: { template: '<div>用户页</div>' } },
      { path: '/admin/banks', component: { template: '<div>题库页</div>' } },
    ],
  })
  await router.push('/admin/users')
  await router.isReady()
  wrapper = mount(AdminLayout, {
    global: { plugins: [pinia, router, ArcoVue], stubs: { DepthRouterView: true } },
  })
  await flushPromises()
  const group = () => wrapper!.get('.admin-sidebar .arco-menu-inline')
  const heading = () => wrapper!.get('.admin-sidebar .arco-menu-inline-header')
  async function navigate(label: string, path: string) {
    const item = wrapper!
      .findAll('.admin-navbar .arco-menu-item')
      .find((node) => node.text() === label)!
    await item.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(path)
  }
  expect(group().get('.arco-menu-inline-content').isVisible()).toBe(true)
  await navigate('内容管理', '/admin/banks')
  expect(group().text()).toContain('题库管理')
  expect(group().get('.arco-menu-inline-content').isVisible()).toBe(true)
  await heading().trigger('click')
  expect(group().get('.arco-menu-icon-suffix').classes()).not.toContain('is-open')
  await navigate('系统管理', '/admin/users')
  expect(group().text()).toContain('用户管理')
  expect(group().get('.arco-menu-inline-content').isVisible()).toBe(true)
  await navigate('内容管理', '/admin/banks')
  expect(group().get('.arco-menu-inline-content').isVisible()).toBe(true)
})
