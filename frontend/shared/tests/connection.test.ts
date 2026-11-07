// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useConnectionStore } from '../src/connection'

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
  it('rejects unsupported base URLs', () => {
    const store = useConnectionStore()
    store.configure('javascript:alert(1)')
    expect(store.configured).toBe(false)
    expect(store.configError).toContain('HTTP')
  })
})
