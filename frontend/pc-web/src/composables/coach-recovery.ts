import { onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { notifyError, type CoachSession } from '@quest/shared'

/** 断流或重新打开页面后只读恢复轮次，不重放付费请求。 */
export function useCoachRecovery(
  session: () => CoachSession | null,
  busy: () => boolean,
  fetch: (id: string, signal: AbortSignal) => Promise<CoachSession>,
  update: (value: CoachSession) => void,
) {
  const paused = ref(false)
  let enabled = true,
    revision = 0,
    attempts = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
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
      busy() ||
      !session()?.processing ||
      document.visibilityState === 'hidden' ||
      paused.value ||
      timer ||
      controller
    )
      return
    timer = setTimeout(poll, 2000)
  }
  async function poll() {
    timer = undefined
    const id = session()?.id
    if (!id || busy()) return
    const version = revision
    const current = new AbortController()
    controller = current
    try {
      const result = await fetch(id, current.signal)
      if (current.signal.aborted || version !== revision || session()?.id !== id || busy()) return
      if (result.id !== id) throw new Error('会话状态与当前对话不一致')
      update(result)
      if (++attempts >= 150 && result.processing) paused.value = true
    } catch (error) {
      if (!current.signal.aborted && version === revision) {
        paused.value = true
        notifyError(error)
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
    attempts = 0
    paused.value = false
    schedule()
  }
  function visibility() {
    stop()
    schedule()
  }
  watch(
    [() => session()?.id, () => session()?.processing, busy],
    () => {
      stop()
      attempts = 0
      paused.value = false
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
