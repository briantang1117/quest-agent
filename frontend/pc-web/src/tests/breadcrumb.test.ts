import { afterEach, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import AdminBreadcrumb from '../components/AdminBreadcrumb.vue'

let wrapper: VueWrapper | undefined
afterEach(() => wrapper?.unmount())
async function setup(path: string, previous?: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/banks', component: { template: '<div />' }, meta: { pageDeepth: 1 } },
      { path: '/admin/banks/:id', component: { template: '<div />' }, meta: { pageDeepth: 2 } },
      { path: '/admin/questions/:id', component: { template: '<div />' }, meta: { pageDeepth: 3 } },
    ],
  })
  if (previous) await router.push(previous)
  await router.push(path)
  await router.isReady()
  // memory history不维护浏览器的back字段，补齐WebHistory提供的真实导航状态。
  if (previous) router.options.history.replace(path, { back: previous })
  wrapper = mount(AdminBreadcrumb, {
    props: {
      items: [
        { label: '内容管理' },
        { label: '公共题库', to: '/admin/banks' },
        { label: '管理题库' },
      ],
      backTo: '/admin/banks',
    },
    global: { plugins: [router, ArcoVue] },
  })
  return { router, wrapper }
}
it('详情页以小号返回入口回到上层，列表页不重复显示返回', async () => {
  const { router, wrapper } = await setup('/admin/banks/b1', '/admin/banks?page=2')
  await wrapper.get('[data-test="breadcrumb-back"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/admin/banks?page=2')
  expect(wrapper.find('[data-test="breadcrumb-back"]').exists()).toBe(false)
})
it('直接进入详情时安全回退到列表，不依赖浏览器外部历史', async () => {
  const { router, wrapper } = await setup('/admin/banks/b1')
  await wrapper.get('[data-test="breadcrumb-back"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/admin/banks')
})
it('面包屑上级可点击且当前项不可点击', async () => {
  const { router, wrapper } = await setup('/admin/questions/q1')
  const links = wrapper.findAll('a')
  await links.find((link) => link.text() === '公共题库')!.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/admin/banks')
  expect(links.some((link) => link.text() === '管理题库')).toBe(false)
})
