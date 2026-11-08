import mitt from 'mitt'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

type RouteChange = {
  to: RouteLocationNormalizedLoaded
  from: RouteLocationNormalizedLoaded
}

// 每个router独立分发，避免测试或多应用实例之间串事件。
const emitters = new WeakMap<Router, ReturnType<typeof createRouteEmitter>>()
function createRouteEmitter(router: Router) {
  const emitter = mitt<{ routeChange: RouteChange }>()
  router.afterEach((to, from, failure) => {
    if (!failure) emitter.emit('routeChange', { to, from })
  })
  return emitter
}

export function setupRouteListener(router: Router) {
  if (!emitters.has(router)) emitters.set(router, createRouteEmitter(router))
}

export function listenerRouteChange(router: Router, handler: (event: RouteChange) => void) {
  setupRouteListener(router)
  const emitter = emitters.get(router)!
  emitter.on('routeChange', handler)
  handler({ to: router.currentRoute.value, from: router.currentRoute.value })
  return () => emitter.off('routeChange', handler)
}
