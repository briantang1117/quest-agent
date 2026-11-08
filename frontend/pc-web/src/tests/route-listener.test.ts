import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { listenerRouteChange, setupRouteListener } from '../utils/route-listener'

async function fixture() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/a', '/b'].map((path) => ({ path, component: { render: () => null } })),
  })
  setupRouteListener(router)
  await router.push('/a')
  return router
}

describe('mitt路由事件', () => {
  it('订阅立即提供当前页面，成功导航只通知一次，退订后不通知', async () => {
    const router = await fixture()
    setupRouteListener(router)
    const handler = vi.fn()
    const remove = listenerRouteChange(router, handler)
    expect(handler.mock.calls[0]?.[0].to.path).toBe('/a')
    await router.push('/b')
    expect(handler).toHaveBeenCalledTimes(2)
    expect(handler.mock.calls[1]?.[0]).toMatchObject({ to: { path: '/b' }, from: { path: '/a' } })
    remove()
    await router.push('/a')
    expect(handler).toHaveBeenCalledTimes(2)
  })

  it('取消或重复导航不通知，两个router不会串事件', async () => {
    const router = await fixture()
    const other = await fixture()
    const handler = vi.fn()
    const otherHandler = vi.fn()
    const remove = listenerRouteChange(router, handler)
    const removeOther = listenerRouteChange(other, otherHandler)
    try {
      await router.push('/a')
      const unguard = router.beforeEach(() => false)
      await router.push('/b')
      expect(handler).toHaveBeenCalledTimes(1)
      unguard()
      await router.push('/b')
      expect(handler).toHaveBeenCalledTimes(2)
      expect(otherHandler).toHaveBeenCalledTimes(1)
    } finally {
      remove()
      removeOther()
    }
  })
})
