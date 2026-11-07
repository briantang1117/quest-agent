// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../src/api'
import { useAuthStore, type TokenResult, type UserProfile } from '../src/auth'
import { createUserApi } from '../src/user-api'

const user: UserProfile = {
  id: '9007199254740993',
  username: 'student_a',
  displayName: '学员',
  role: 'student',
  status: 'active',
  mustChangePassword: true,
  createdAt: '2026-11-07T00:00:00Z',
  lastLoginAt: null,
}
const result = (name = 'first'): TokenResult => ({
  accessToken: name,
  expiresAt: new Date(Date.now() + 7200000).toISOString(),
  user: { ...user },
})
function response(config: InternalAxiosRequestConfig, data: unknown, code = 0) {
  return {
    config,
    data: { code, message: code ? '请求被拒绝' : 'ok', data, traceId: 'trace-auth' },
    status: code === 40100 ? 401 : 200,
    statusText: 'OK',
    headers: {},
  }
}
const originalAdapter = axios.defaults.adapter
function setup(adapter: AxiosAdapter) {
  axios.defaults.adapter = adapter
  const auth = useAuthStore()
  auth.configure('/gateway')
  return auth
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  localStorage.clear()
})
afterEach(() => {
  useAuthStore().clearSession()
  axios.defaults.adapter = originalAdapter
  vi.useRealTimers()
})

describe('持久化凭据恢复与会话竞态', () => {
  it('登录保留密码空格且只持久化token，刷新后通过me恢复身份', async () => {
    const auth = setup(async (config) => {
      expect(config.url).toBe('/api/v1/auth/login')
      expect(JSON.parse(config.data)).toEqual({ username: 'student_a', password: ' password1234 ' })
      expect(config.headers.has('Authorization')).toBe(false)
      return response(config, result())
    })
    await auth.login({ username: 'student_a', password: ' password1234 ' })
    expect(auth.user?.id).toBe('9007199254740993')
    expect(auth.authenticated).toBe(true)
    expect(localStorage.length).toBe(1)
    const stored = JSON.parse(localStorage.getItem('quest.auth.session')!)
    expect(stored.accessToken).toBe('first')
    expect(stored).not.toHaveProperty('user')
    expect(stored).not.toHaveProperty('password')
    expect(sessionStorage.length).toBe(0)
    axios.defaults.adapter = async (config) => {
      expect(config.url).toBe('/api/v1/auth/me')
      expect(config.headers.get('Authorization')).toBe('Bearer first')
      return response(config, user)
    }
    setActivePinia(createPinia())
    const restored = useAuthStore()
    restored.configure('/gateway')
    expect(restored.authenticated).toBe(false)
    await restored.restoreSession()
    expect(restored.authenticated).toBe(true)
    expect(restored.user?.id).toBe(user.id)
    auth.clearSession()
  })
  it('恢复时401清除缓存且不恢复任何身份', async () => {
    localStorage.setItem(
      'quest.auth.session',
      JSON.stringify({
        baseURL: '/gateway',
        accessToken: 'revoked',
        expiresAt: result().expiresAt,
      }),
    )
    const auth = setup(async (config) => response(config, null, 40100))
    await auth.restoreSession()
    expect(auth.authenticated).toBe(false)
    expect(localStorage.getItem('quest.auth.session')).toBeNull()
  })
  it('过期、损坏或服务地址不匹配的缓存不会发出请求', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => response(config, user))
    const auth = setup(adapter)
    for (const value of [
      'invalid-json',
      JSON.stringify({
        baseURL: '/gateway',
        accessToken: 'old',
        expiresAt: new Date(Date.now() - 1).toISOString(),
      }),
      JSON.stringify({
        baseURL: 'http://other',
        accessToken: 'other',
        expiresAt: result().expiresAt,
      }),
    ]) {
      localStorage.setItem('quest.auth.session', value)
      await auth.restoreSession()
      expect(auth.authenticated).toBe(false)
    }
    expect(adapter).not.toHaveBeenCalled()
  })
  it('恢复请求晚于登出不能复活身份或缓存', async () => {
    localStorage.setItem(
      'quest.auth.session',
      JSON.stringify({ baseURL: '/gateway', accessToken: 'old', expiresAt: result().expiresAt }),
    )
    const pending = deferred<ReturnType<typeof response>>()
    let request!: InternalAxiosRequestConfig
    const auth = setup(async (config) => {
      request = config
      return pending.promise
    })
    const restore = auth.restoreSession()
    await vi.waitFor(() => expect(request).toBeDefined())
    auth.clearSession()
    pending.resolve(response(request, user))
    await restore
    expect(auth.authenticated).toBe(false)
    expect(localStorage.getItem('quest.auth.session')).toBeNull()
  })
  it('到期自动清除token和身份', async () => {
    const auth = setup(async (config) => response(config, result()))
    await auth.login({ username: 'student_a', password: 'password1234' })
    await vi.advanceTimersByTimeAsync(7200000)
    expect(auth.user).toBeNull()
    expect(auth.token).toBe('')
  })
  it('401清会话且原样保留ApiError traceId', async () => {
    const auth = setup(async (config) =>
      response(
        config,
        config.url?.endsWith('/login') ? result() : null,
        config.url?.endsWith('/login') ? 0 : 40100,
      ),
    )
    await auth.login({ username: 'student_a', password: 'password1234' })
    await expect(auth.loadMe()).rejects.toMatchObject({ code: 40100, traceId: 'trace-auth' })
    expect(auth.user).toBeNull()
  })
  it('登出立即清本地，未完成login不得恢复身份', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    let loginConfig!: InternalAxiosRequestConfig
    const auth = setup(async (config) => {
      loginConfig = config
      return pending.promise
    })
    const login = auth.login({ username: 'student_a', password: 'password1234' })
    await vi.waitFor(() => expect(loginConfig).toBeDefined())
    await auth.logout()
    pending.resolve(response(loginConfig, result()))
    await expect(login).rejects.toBeInstanceOf(ApiError)
    expect(auth.authenticated).toBe(false)
  })
  it('旧login和me不能覆盖新身份，旧请求401不能登出新身份', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    let oldConfig!: InternalAxiosRequestConfig
    const auth = setup(async (config) => {
      if (config.url?.endsWith('/me')) {
        oldConfig = config
        return pending.promise
      }
      return response(config, result(JSON.parse(config.data).username))
    })
    await auth.login({ username: 'first', password: 'password1234' })
    const me = auth.loadMe()
    await vi.waitFor(() => expect(oldConfig).toBeDefined())
    await auth.login({ username: 'second', password: 'password1234' })
    pending.resolve(response(oldConfig, null, 40100))
    await expect(me).rejects.toBeInstanceOf(ApiError)
    expect(auth.token).toBe('second')
  })
  it('并发登录仅最后一次可写入', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    let oldConfig!: InternalAxiosRequestConfig
    const auth = setup(async (config) => {
      if (JSON.parse(config.data).username === 'first') {
        oldConfig = config
        return pending.promise
      }
      return response(config, result('second'))
    })
    const first = auth.login({ username: 'first', password: 'password1234' })
    await vi.waitFor(() => expect(oldConfig).toBeDefined())
    await auth.login({ username: 'second', password: 'password1234' })
    pending.resolve(response(oldConfig, result('first')))
    await expect(first).rejects.toBeInstanceOf(ApiError)
    expect(auth.token).toBe('second')
  })
  it('改密替换会话，登出请求带原token并清本地', async () => {
    const seen: Array<[string | undefined, unknown, unknown]> = []
    const auth = setup(async (config) => {
      seen.push([
        config.url,
        config.headers.get('Authorization'),
        config.data && JSON.parse(config.data),
      ])
      return response(
        config,
        config.url?.endsWith('/logout')
          ? null
          : {
              ...result(config.url?.endsWith('/login') ? 'old' : 'new'),
              user: { ...user, mustChangePassword: false },
            },
      )
    })
    await auth.login({ username: 'student_a', password: 'password1234' })
    await auth.changePassword({ currentPassword: 'password1234', newPassword: 'newpassword123' })
    expect(auth.token).toBe('new')
    await auth.logout()
    expect(seen).toContainEqual([
      '/api/v1/auth/change-password',
      'Bearer old',
      { currentPassword: 'password1234', newPassword: 'newpassword123' },
    ])
    expect(seen).toContainEqual(['/api/v1/auth/logout', 'Bearer new', {}])
    expect(auth.token).toBe('')
  })
  it('主动登出后卸载组件的请求不会把退出误标为过期', async () => {
    const transport = vi.fn(async (config: InternalAxiosRequestConfig) =>
      response(config, config.url?.endsWith('/logout') ? null : result()),
    )
    const auth = setup(transport)
    await auth.login({ username: 'student_a', password: 'password1234' })
    await auth.logout()
    await expect(auth.request({ url: '/api/v1/admin/users' })).rejects.toMatchObject({
      code: 40100,
    })
    expect(auth.sessionNotice).toBe('')
    expect(auth.authenticated).toBe(false)
    expect(localStorage.getItem('quest.auth.session')).toBeNull()
    expect(transport).toHaveBeenCalledTimes(2)
  })
  it('旧me成功响应在登出后不会恢复身份', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    let config!: InternalAxiosRequestConfig
    const auth = setup(async (request) => {
      if (request.url?.endsWith('/me')) {
        config = request
        return pending.promise
      }
      return response(request, request.url?.endsWith('/logout') ? null : result())
    })
    await auth.login({ username: 'student_a', password: 'password1234' })
    const me = auth.loadMe()
    await vi.waitFor(() => expect(config).toBeDefined())
    await auth.logout()
    pending.resolve(response(config, user))
    await expect(me).rejects.toBeInstanceOf(ApiError)
    expect(auth.user).toBeNull()
  })
  it('40310更新强制改密状态且保留traceId', async () => {
    const auth = setup(async (config) =>
      response(
        config,
        config.url?.endsWith('/login')
          ? { ...result(), user: { ...user, mustChangePassword: false } }
          : null,
        config.url?.endsWith('/login') ? 0 : 40310,
      ),
    )
    await auth.login({ username: 'student_a', password: 'password1234' })
    await expect(auth.request({ url: '/api/v1/admin/users' })).rejects.toMatchObject({
      code: 40310,
      traceId: 'trace-auth',
    })
    expect(auth.user?.mustChangePassword).toBe(true)
  })
  it('HTTP401即便缺少业务码也清会话', async () => {
    const auth = setup(async (config) => {
      if (config.url?.endsWith('/login')) return response(config, result())
      throw {
        isAxiosError: true,
        response: { status: 401, data: { message: '失效', traceId: 'http401' } },
      }
    })
    await auth.login({ username: 'student_a', password: 'password1234' })
    await expect(auth.loadMe()).rejects.toMatchObject({ status: 401, traceId: 'http401' })
    expect(auth.token).toBe('')
  })
  it('未配置时不发登录请求，旧过期token不会再次发出', async () => {
    const transport = vi.fn(async (config: InternalAxiosRequestConfig) =>
      response(config, result()),
    )
    const auth = setup(transport)
    auth.configure('')
    await expect(
      auth.login({ username: 'student_a', password: 'password1234' }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(transport).not.toHaveBeenCalled()
    auth.configure('/gateway')
    await auth.login({ username: 'student_a', password: 'password1234' })
    vi.setSystemTime(Date.now() + 7200001)
    await expect(auth.loadMe()).rejects.toBeInstanceOf(ApiError)
    expect(transport).toHaveBeenCalledTimes(1)
    expect(auth.token).toBe('')
  })
  it('过期登录结果不接纳', async () => {
    const auth = setup(async (config) =>
      response(config, { ...result(), expiresAt: new Date(Date.now() - 1).toISOString() }),
    )
    await expect(
      auth.login({ username: 'student_a', password: 'password1234' }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(auth.authenticated).toBe(false)
  })
  it('禁用身份或已过期会话不会被接纳', async () => {
    const auth = setup(async (config) =>
      response(config, { ...result(), user: { ...user, status: 'disabled' } }),
    )
    await expect(
      auth.login({ username: 'student_a', password: 'password1234' }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(auth.authenticated).toBe(false)
  })
})

describe('用户管理wire契约', () => {
  it('分页筛选、创建、PATCH状态和重置使用string ID', async () => {
    const seen: InternalAxiosRequestConfig[] = []
    const auth = setup(async (config) => {
      seen.push(config)
      return response(config, config.url?.endsWith('/login') ? result() : user)
    })
    await auth.login({ username: 'admin', password: 'password1234' })
    const api = createUserApi(auth)
    await api.list({ page: 2, size: 20, keyword: '学员', status: 'disabled' })
    await api.create({
      username: 'student_a',
      displayName: '学员',
      initialPassword: 'password1234',
    })
    await api.setStatus(user.id, 'disabled')
    await api.resetPassword(user.id, 'newpassword123')
    expect(seen[1]).toMatchObject({
      method: 'get',
      url: '/api/v1/admin/users',
      params: { page: 2, size: 20, keyword: '学员', status: 'disabled' },
    })
    expect(seen[2]?.data).toBe(
      JSON.stringify({
        username: 'student_a',
        displayName: '学员',
        initialPassword: 'password1234',
      }),
    )
    expect(seen[3]).toMatchObject({
      method: 'patch',
      url: `/api/v1/admin/users/${user.id}/status`,
      data: '{"status":"disabled"}',
    })
    expect(seen[4]).toMatchObject({
      method: 'post',
      url: `/api/v1/admin/users/${user.id}/reset-password`,
      data: '{"newPassword":"newpassword123"}',
    })
  })
})
