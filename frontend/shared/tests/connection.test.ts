// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useConnectionStore } from '../src/connection'
import axios from 'axios'
import { installErrorFeedback } from '../src/feedback'
let cleanup: (() => void) | undefined
const originalAdapter = axios.defaults.adapter
afterEach(() => {
  cleanup?.()
  axios.defaults.adapter = originalAdapter
})

describe('connection and in-memory auth', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })
  it('does not probe without configuration or automatically on startup', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch')
    const store = useConnectionStore()
    expect(store.health).toBe('未检查')
    await store.checkHealth()
    expect(fetcher).not.toHaveBeenCalled()
    expect(store.health).toContain('未配置')
  })
  it('checks only public health and reports actual dependency status', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'DOWN' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const store = useConnectionStore()
    store.configure('/gateway')
    await store.checkHealth()
    expect(fetcher.mock.calls[0]?.[0]).toBe('/gateway/actuator/health')
    expect(store.health).toContain('DOWN')
    expect(store.health).toContain('503')
    expect(show).toHaveBeenCalledTimes(1)
  })
  it('does not send credentials to public health', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"status":"UP"}'))
    const store = useConnectionStore()
    store.configure('/gateway')
    store.setToken('  Bearer abc  ')
    await store.checkHealth()
    expect(store.token).toBe('abc')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).has('Authorization')).toBe(false)
    expect(window.sessionStorage.length).toBe(0)
    expect(window.localStorage.length).toBe(0)
  })
  it('requires token for protected system info and clears sensitive data on token change', async () => {
    const store = useConnectionStore()
    store.configure('/gateway')
    await store.checkSystem()
    expect(store.systemStatus).toContain('Token')
    store.systemInfo = { service: 'old' }
    store.setToken('new')
    expect(store.systemInfo).toBeNull()
    store.clearToken()
    expect(store.token).toBe('')
  })
  it('supports explicitly configured same-origin root without double slashes', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"status":"UP"}'))
    const store = useConnectionStore()
    store.configure('/')
    expect(store.configured).toBe(true)
    await store.checkHealth()
    expect(fetcher.mock.calls[0]?.[0]).toBe('/actuator/health')
  })
  it('诊断业务错误仅全局反馈一次，不在状态中泄漏上游或traceId', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    axios.defaults.adapter = async (config) => ({
      config,
      status: 403,
      statusText: 'Forbidden',
      headers: {},
      data: { code: 40300, message: 'Access denied', data: null, traceId: 'trace-secret' },
    })
    const store = useConnectionStore()
    store.configure('/gateway')
    store.setToken('token')
    await store.checkSystem()
    expect(store.systemStatus).toBe('凭据未验证')
    expect(show).toHaveBeenCalledTimes(1)
  })
  it('健康网络失败同样全局反馈，诊断状态不重复错误文案', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('secret upstream'))
    const store = useConnectionStore()
    store.configure('/gateway')
    await store.checkHealth()
    expect(store.health).toBe('未能完成检查')
    expect(show).toHaveBeenCalledTimes(1)
  })
  it('rejects unsupported base URLs', () => {
    const store = useConnectionStore()
    store.configure('javascript:alert(1)')
    expect(store.configured).toBe(false)
    expect(store.configError).toContain('HTTP')
  })
})
