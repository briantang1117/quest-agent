import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { useAuthStore } from '@quest/shared'
import AdminUserControlsView from '../views/AdminUserControlsView.vue'
import AdminStatisticsView from '../views/AdminStatisticsView.vue'
import AdminEventsView from '../views/AdminEventsView.vue'
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
it('用户授权从后台读取，修改高级功能后重新读取权威状态', async () => {
  const pinia = createPinia(),
    auth = useAuthStore(pinia)
  let enabled = false
  const request = vi.spyOn(auth, 'request').mockImplementation(async (config) => {
    if (config.method === 'PATCH') {
      enabled = true
      return null as never
    }
    return {
      features: { materials: enabled },
      models: [{ model: 'model', credited: 1000, consumed: 26, reserved: 0, available: 974 }],
      usage: [],
    } as never
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/users/:id/controls', component: AdminUserControlsView }],
  })
  await router.push('/admin/users/12/controls')
  await router.isReady()
  const wrapper = mount(AdminUserControlsView, { global: { plugins: [pinia, router, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('高级功能')
  expect(wrapper.text()).toContain('974')
  await wrapper.get('[data-feature="materials"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith({
    method: 'PATCH',
    url: '/api/v1/admin/users/12/features/materials',
    data: { enabled: true },
  })
  expect(wrapper.get('[data-feature="materials"]').attributes('aria-checked')).toBe('true')
})
it('单用户统计保留userId筛选并展示模型用量', async () => {
  const pinia = createPinia(),
    auth = useAuthStore(pinia)
  const request = vi.spyOn(auth, 'request').mockResolvedValue({
    api: [],
    ai: [
      {
        model: 'test-model',
        capability: 'coach',
        calls: 2,
        failed: 0,
        input_tokens: 17,
        output_tokens: 9,
        pending_tokens: 0,
      },
    ],
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/statistics', component: AdminStatisticsView }],
  })
  await router.push('/admin/statistics?userId=12')
  await router.isReady()
  const wrapper = mount(AdminStatisticsView, { global: { plugins: [pinia, router, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(request.mock.calls[0]?.[0].params.userId).toBe('12')
  expect(wrapper.text()).toContain('test-model')
  expect(wrapper.text()).toContain('17')
})

it('统计服务不可用时展示失败状态，不生成零用量记录', async () => {
  const pinia = createPinia(),
    auth = useAuthStore(pinia)
  vi.spyOn(auth, 'request').mockRejectedValue(new Error('offline'))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/statistics', component: AdminStatisticsView }],
  })
  await router.push('/admin/statistics')
  await router.isReady()
  const wrapper = mount(AdminStatisticsView, { global: { plugins: [pinia, router, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('统计加载失败')
  expect(wrapper.find('table').exists()).toBe(false)
})

it('日志翻页与调整每页数量保留筛选，清空后回到第一页', async () => {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  const request = vi.spyOn(auth, 'request').mockResolvedValue({ list: [], total: 120 })
  const wrapper = mount(AdminEventsView, { global: { plugins: [pinia, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  await wrapper.get('input').setValue('12')
  wrapper.findComponent({ name: 'Pagination' }).vm.$emit('change', 2)
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toMatchObject({ userId: '12', page: 2, size: 20 })
  wrapper.findComponent({ name: 'Pagination' }).vm.$emit('pageSizeChange', 50)
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toMatchObject({ userId: '12', page: 1, size: 50 })
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '清空')!
    .trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toMatchObject({
    userId: '',
    kind: '',
    page: 1,
    size: 50,
  })
})

it('统一额度充值无需模型与原因，校验和成功结果使用顶部 Message', async () => {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  const success = vi
    .spyOn(Message, 'success')
    .mockImplementation(() => ({ close: vi.fn() }) as never)
  const warning = vi
    .spyOn(Message, 'warning')
    .mockImplementation(() => ({ close: vi.fn() }) as never)
  const request = vi
    .spyOn(auth, 'request')
    .mockResolvedValue({ features: {}, models: [], usage: [] })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/users/:id/controls', component: AdminUserControlsView }],
  })
  await router.push('/admin/users/12/controls')
  await router.isReady()
  const wrapper = mount(AdminUserControlsView, { global: { plugins: [pinia, router, ArcoVue] } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).not.toContain('模型标识')
  expect(wrapper.text()).toContain('充值原因（选填）')
  const form = wrapper.findComponent({ name: 'Form' })
  const amount = wrapper.findComponent({ name: 'InputNumber' })
  amount.vm.$emit('update:modelValue', undefined)
  await wrapper.vm.$nextTick()
  form.vm.$emit('submitSuccess', {})
  await flushPromises()
  expect(warning).toHaveBeenCalled()
  expect(request).toHaveBeenCalledTimes(1)
  amount.vm.$emit('update:modelValue', 1000)
  await wrapper.vm.$nextTick()
  form.vm.$emit('submitSuccess', {})
  await flushPromises()
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    url: '/api/v1/admin/users/12/credits',
    data: { amount: 1000, reason: '', requestId: expect.any(String) },
  })
  expect(success).toHaveBeenCalledWith('充值成功')
})
