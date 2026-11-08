<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, type VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listenerRouteChange } from '../utils/route-listener'

const route = useRoute()
const pages = ref<{ name: string; path: string; depth: number }[]>([])
const included = computed(() => pages.value.map((page) => page.name))
function bindInstance(vnode: VNode) {
  const record = route.matched[route.matched.length - 1]
  void nextTick(() => {
    if (record && vnode.component && !vnode.component.isUnmounted) {
      record.instances.default = vnode.component.proxy
    }
  })
}
const removeListener = listenerRouteChange(useRouter(), async ({ to }) => {
  const path = to.fullPath
  const depth = to.meta.pageDeepth
  const name = to.name
  const existing = pages.value.find((page) => page.path === path)
  await nextTick()
  if (route.fullPath !== path) return
  pages.value = pages.value.filter(
    (page) => typeof depth === 'number' && (page.depth < depth || page === existing),
  )
  if (existing || typeof depth !== 'number' || depth <= 0 || typeof name !== 'string') return
  // 先淘汰同层旧实例，再纳入新页，避免相同组件不同参数的旧缓存残留。
  await nextTick()
  if (route.fullPath === path) pages.value.push({ name, path, depth })
})
onBeforeUnmount(removeListener)
</script>

<template>
  <RouterView v-slot="{ Component, route: pageRoute }">
    <KeepAlive :include="included">
      <component
        :is="Component"
        :key="pageRoute.fullPath"
        @vue:mounted="bindInstance"
        @vue:updated="bindInstance"
      />
    </KeepAlive>
  </RouterView>
</template>
