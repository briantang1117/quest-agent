import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { useAuthStore, type UserProfile } from '@quest/shared'
import { createAppRouter } from '../router'
import App from '../App.vue'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
})

it.each(['admin', 'student', 'anonymous'] as const)(
  '刷新时等待真实身份恢复，%s身份未确认前不渲染普通外壳',
  async (role) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    let complete!: () => void
    const pending = new Promise<void>((resolve) => {
      complete = resolve
    })
    vi.spyOn(auth, 'restoreSession').mockImplementation(async () => {
      await pending
      auth.user =
        role === 'anonymous'
          ? null
          : ({
              id: '1',
              username: role,
              displayName: '测试身份',
              role,
              status: 'active',
              mustChangePassword: false,
              createdAt: '2026-11-08T00:00:00Z',
              lastLoginAt: null,
            } satisfies UserProfile)
    })
    const router = createAppRouter(createMemoryHistory(), pinia)
    const navigation = router.push(role === 'student' ? '/practice' : '/admin/users')
    wrapper = mount(App, {
      global: {
        plugins: [pinia, router, ArcoVue],
        stubs: { RouterView: { template: '<div data-test="resolved-page" />' } },
      },
    })
    await flushPromises()
    expect(wrapper.find('.topbar').exists()).toBe(false)
    expect(wrapper.find('.site-footer').exists()).toBe(false)
    expect(wrapper.find('[data-test="resolved-page"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toBe('正在加载')
    complete()
    await navigation
    await router.isReady()
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="resolved-page"]').exists()).toBe(true)
    expect(wrapper.find('.topbar').exists()).toBe(role !== 'admin')
    expect(router.currentRoute.value.path).toBe(
      role === 'anonymous' ? '/login' : role === 'admin' ? '/admin/users' : '/practice',
    )
  },
)
