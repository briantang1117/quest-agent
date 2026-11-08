/* eslint-disable vue/one-component-per-file -- 多个内联组件用于验证路由实例生命周期。 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, onUnmounted, ref, watch } from 'vue'
import { createPinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { useAuthStore, type UserProfile } from '@quest/shared'
import AdminLayout from './layouts/AdminLayout.vue'
import PracticeLayout from './layouts/PracticeLayout.vue'
import { createAppRouter } from './router'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  useRoute,
  type RouteRecordRaw,
} from 'vue-router'
import DepthRouterView from './components/DepthRouterView.vue'

const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.unstubAllGlobals()
})

async function fixture() {
  let serial = 0
  const unmounted: number[] = []
  const observed: string[] = []
  const blockLeave = ref(false)
  const leaveCalls = vi.fn()
  const page = (name: string) =>
    defineComponent({
      name,
      beforeRouteLeave() {
        leaveCalls()
        return !blockLeave.value
      },
      setup() {
        const instance = ++serial
        const route = useRoute()
        onUnmounted(() => unmounted.push(instance))
        watch(
          () => route.fullPath,
          (path) => observed.push(`${instance}:${path}`),
        )
        const value = ref('')
        return () =>
          h('section', [
            h('span', { 'data-instance': '' }, String(instance)),
            h('input', {
              value: value.value,
              onInput: (event: Event) => (value.value = (event.target as HTMLInputElement).value),
            }),
          ])
      },
    })
  const routes: RouteRecordRaw[] = [
    { path: '/list', name: 'List', component: page('List'), meta: { pageDeepth: 1 } },
    { path: '/other', name: 'Other', component: page('Other'), meta: { pageDeepth: 1 } },
    { path: '/detail/:id', name: 'Detail', component: page('Detail'), meta: { pageDeepth: 2 } },
    { path: '/edit', name: 'Edit', component: page('Edit'), meta: { pageDeepth: 3 } },
    { path: '/uncached', component: page('Uncached') },
  ]
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/list')
  await router.isReady()
  const identity = ref('user-a')
  const host = defineComponent({
    setup: () => () => h(DepthRouterView, { key: identity.value }),
  })
  const wrapper = mount(host, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  const go = async (path: string) => {
    await router.push(path)
    await flushPromises()
  }
  const instance = () => wrapper.get('[data-instance]').text()
  const input = () => wrapper.get<HTMLInputElement>('input').element.value
  return {
    router,
    wrapper,
    go,
    instance,
    input,
    identity,
    unmounted,
    observed,
    blockLeave,
    leaveCalls,
  }
}

describe('页面深度单向缓存', () => {
  it('1→2→3→2→1复用浅层实例与输入，再进深层重新创建', async () => {
    const { wrapper, go, instance, input } = await fixture()
    const list = instance()
    await wrapper.get('input').setValue('列表筛选')
    await go('/detail/a')
    const detail = instance()
    await wrapper.get('input').setValue('审核内容')
    await go('/edit')
    const edit = instance()
    await go('/detail/a')
    expect(instance()).toBe(detail)
    expect(input()).toBe('审核内容')
    await go('/edit')
    expect(instance()).not.toBe(edit)
    await go('/list')
    expect(instance()).toBe(list)
    expect(input()).toBe('列表筛选')
    await go('/detail/a')
    expect(instance()).not.toBe(detail)
    expect(input()).toBe('')
  })

  it('浏览器back返回保留列表实例', async () => {
    const { wrapper, router, go, instance, input } = await fixture()
    const list = instance()
    await wrapper.get('input').setValue('第2页')
    await go('/detail/a')
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/list'))
    await flushPromises()
    expect(instance()).toBe(list)
    expect(input()).toBe('第2页')
  })

  it('同层页面切换销毁旧实例', async () => {
    const { go, instance } = await fixture()
    const list = instance()
    await go('/other')
    await go('/list')
    expect(instance()).not.toBe(list)
  })

  it('相同组件不同参数、查询不会复用已离开的实例', async () => {
    const { wrapper, go, instance, input } = await fixture()
    await go('/detail/a')
    const first = instance()
    await wrapper.get('input').setValue('a的草稿')
    await go('/detail/b')
    expect(instance()).not.toBe(first)
    expect(input()).toBe('')
    await go('/detail/a')
    expect(instance()).not.toBe(first)
    const beforeQuery = instance()
    await go('/detail/a?tab=source')
    expect(instance()).not.toBe(beforeQuery)
  })

  it('未配置深度的页面不缓存并清空访问链', async () => {
    const { go, instance } = await fixture()
    const list = instance()
    await go('/uncached')
    const uncached = instance()
    await go('/list')
    expect(instance()).not.toBe(list)
    await go('/uncached')
    expect(instance()).not.toBe(uncached)
  })

  it('取消导航不会清除原来的访问链', async () => {
    const { router, go, instance } = await fixture()
    const list = instance()
    await go('/detail/a')
    const detail = instance()
    const remove = router.beforeEach((to) => (to.path === '/other' ? false : true))
    await go('/other')
    expect(instance()).toBe(detail)
    remove()
    await go('/list')
    expect(instance()).toBe(list)
  })

  it('权限重定向回原页不会破坏缓存', async () => {
    const { router, go, instance } = await fixture()
    const list = instance()
    await go('/detail/a')
    router.beforeEach((to) => (to.path === '/other' ? '/detail/a' : true))
    await go('/other')
    await go('/list')
    expect(instance()).toBe(list)
  })

  it('返回浅层时深层实例确实卸载', async () => {
    const { go, instance, unmounted, wrapper } = await fixture()
    const list = Number(instance())
    await go('/detail/a')
    const detail = Number(instance())
    expect(unmounted).not.toContain(list)
    await go('/list')
    expect(unmounted).toContain(detail)
    wrapper.unmount()
    expect(unmounted).toContain(list)
  })

  it('恢复缓存页面后仍执行组件离开守卫并取消导航', async () => {
    const { router, go, blockLeave, leaveCalls, instance } = await fixture()
    const list = instance()
    await go('/detail/a')
    await go('/list')
    expect(instance()).toBe(list)
    leaveCalls.mockClear()
    blockLeave.value = true
    await go('/other')
    expect(leaveCalls).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/list')
    expect(instance()).toBe(list)
  })

  it('同路由不同参数切换后仍执行组件离开守卫', async () => {
    const { router, go, blockLeave, leaveCalls } = await fixture()
    await go('/detail/a')
    await go('/detail/b')
    leaveCalls.mockClear()
    blockLeave.value = true
    await go('/other')
    expect(leaveCalls).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/detail/b')
  })

  it('认证作用域变化销毁所有缓存实例', async () => {
    const { go, instance, identity, unmounted } = await fixture()
    const list = instance()
    await go('/detail/a')
    const detail = instance()
    identity.value = 'user-b'
    await nextTick()
    expect(instance()).not.toBe(detail)
    expect(unmounted).toEqual(expect.arrayContaining([Number(list), Number(detail)]))
    await go('/list')
    expect(instance()).not.toBe(list)
  })
})

describe('PC路由缓存接入', () => {
  it('列表深度1、题库管理深度2、审核深度3，非业务页不缓存', () => {
    const router = createAppRouter(createMemoryHistory(), createPinia())
    for (const path of ['/admin/users', '/admin/banks', '/practice', '/practice/agent']) {
      expect(router.resolve(path).meta.pageDeepth).toBe(1)
    }
    expect(router.resolve('/admin/banks/test-id').meta.pageDeepth).toBe(2)
    expect(router.resolve('/admin/questions/test-id').meta.pageDeepth).toBe(3)
    for (const path of ['/login', '/change-password', '/dev/connection', '/missing']) {
      expect(router.resolve(path).meta.pageDeepth).toBeUndefined()
    }
  })

  it('scrollBehavior原样恢复历史位置，其余跳转回到顶部', () => {
    const router = createAppRouter(createMemoryHistory(), createPinia())
    const scroll = router.options.scrollBehavior!
    const route = router.currentRoute.value
    const savedPosition = { left: 12, top: 640 }
    expect(scroll(route, route, savedPosition)).toBe(savedPosition)
    expect(scroll(route, route, null)).toEqual({ top: 0 })
  })

  for (const [name, layout] of [
    ['Admin', AdminLayout],
    ['Practice', PracticeLayout],
  ] as const) {
    it(`${name}布局在会话清除时卸载现有缓存页面`, async () => {
      const pinia = createPinia()
      const auth = useAuthStore(pinia)
      auth.token = 'token-a'
      auth.user = {
        id: 'user-a',
        displayName: '管理员',
        role: 'admin',
        status: 'active',
        mustChangePassword: false,
      } as UserProfile
      let serial = 0
      const page = defineComponent({
        setup: () => {
          const instance = ++serial
          return () => h('span', { 'data-instance': '' }, String(instance))
        },
      })
      const router = createRouter({
        history: createMemoryHistory(),
        routes: [
          {
            path: '/admin',
            component: layout,
            children: [{ path: 'users', component: page, meta: { pageDeepth: 1 } }],
          },
        ],
      })
      await router.push('/admin/users')
      const wrapper = mount(RouterView, { global: { plugins: [pinia, router, ArcoVue] } })
      wrappers.push(wrapper)
      await flushPromises()
      const original = wrapper.get('[data-instance]').text()
      auth.clearSession()
      await flushPromises()
      expect(wrapper.get('[data-instance]').text()).not.toBe(original)
    })
  }
})
