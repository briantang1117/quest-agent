import { onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import {
  createMaterialsApi,
  isActiveMaterial,
  notifyError,
  useAuthStore,
  type Material,
} from '@quest/shared'

/** 只查询活动资料，不读取或刷新普通会话；隐藏、切页和卸载均撤销本地轮询。 */
export function useMaterialPolling(items: () => Material[], update: (value: Material) => void) {
  const api = createMaterialsApi(useAuthStore())
  const paused = ref(false)
  let enabled = true
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let revision = 0
  function stop() {
    revision++
    clearTimeout(timer)
    timer = undefined
    controller?.abort()
    controller = undefined
  }
  function schedule() {
    if (
      !enabled ||
      document.visibilityState === 'hidden' ||
      paused.value ||
      controller ||
      timer ||
      !items().some(isActiveMaterial)
    )
      return
    timer = setTimeout(poll, 3000)
  }
  async function poll() {
    timer = undefined
    const version = revision
    const active = items().filter(isActiveMaterial)
    const current = new AbortController()
    controller = current
    try {
      for (const item of active) {
        const value = await api.get(item.id, current.signal)
        if (current.signal.aborted || version !== revision) return
        update(value)
      }
    } catch (cause) {
      if (!current.signal.aborted && version === revision) {
        paused.value = true
        notifyError(cause)
      }
    } finally {
      if (version === revision) {
        controller = undefined
        schedule()
      }
    }
  }
  function resume() {
    stop()
    paused.value = false
    schedule()
  }
  function visibility() {
    stop()
    schedule()
  }
  watch(
    () =>
      items()
        .map((item) => `${item.id}:${item.status}`)
        .join('|'),
    () => {
      if (!items().some(isActiveMaterial)) stop()
      schedule()
    },
    { immediate: true },
  )
  document.addEventListener('visibilitychange', visibility)
  onActivated(() => {
    enabled = true
    schedule()
  })
  onDeactivated(() => {
    enabled = false
    stop()
  })
  onBeforeUnmount(() => {
    enabled = false
    stop()
    document.removeEventListener('visibilitychange', visibility)
  })
  return { paused, resume }
}
