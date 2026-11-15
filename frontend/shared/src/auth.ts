import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref } from 'vue'
import { ApiError, createApiClient, type ApiRequestConfig } from './api'
import { errorMessage, notifyError } from './feedback'

export type UserRole = 'admin' | 'student'
export type UserStatus = 'active' | 'disabled'
export interface UserProfile {
  id: string
  username: string
  displayName: string
  role: UserRole
  status: UserStatus
  mustChangePassword: boolean
  createdAt: string
  lastLoginAt: string | null
}
export interface TokenResult {
  accessToken: string
  expiresAt: string
  user: UserProfile
}
export interface LoginInput {
  username: string
  password: string
  remember?: boolean
}
export interface ChangePasswordInput {
  currentPassword: string
  newPassword: string
}

export { errorMessage } from './feedback'

const SESSION_KEY = 'quest.auth.session'
function readSession(): {
  accessToken: string
  expiresAt: string
  baseURL: string
  remember?: boolean
} | null {
  try {
    const value = JSON.parse(
      sessionStorage.getItem(SESSION_KEY) ?? localStorage.getItem(SESSION_KEY) ?? 'null',
    )
    return value &&
      typeof value.accessToken === 'string' &&
      typeof value.expiresAt === 'string' &&
      typeof value.baseURL === 'string'
      ? value
      : null
  } catch {
    return null
  }
}
function removeSession() {
  try {
    localStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(SESSION_KEY)
  } catch {
    /* 存储被浏览器禁用时仍可使用内存会话。 */
  }
}
// 只持久化凭据/到期时间/服务地址；角色和用户资料必须重新向服务端获取。
export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const sessionEpoch = ref(0)
  const user = ref<UserProfile | null>(null)
  const expiresAt = ref('')
  const baseURL = ref('')
  const configError = ref('未配置 VITE_API_BASE_URL，不会发送请求')
  const sessionNotice = ref('')
  const features = ref<Record<string, boolean>>({})
  let remember = false
  let refreshPending: Promise<void> | undefined
  const configured = computed(() => Boolean(baseURL.value) && !configError.value)
  const authenticated = computed(() =>
    Boolean(token.value && user.value && user.value.status === 'active'),
  )
  let revision = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  let restorePending: Promise<void> | undefined
  function clearSession(notice = '', discardStored = true) {
    if (discardStored) removeSession()
    revision++
    sessionEpoch.value++
    clearTimeout(timer)
    token.value = ''
    user.value = null
    expiresAt.value = ''
    features.value = {}
    sessionNotice.value = notice
  }
  function configure(value: string | undefined) {
    clearSession('', false)
    const raw = value?.trim() ?? ''
    const url = raw === '/' ? '/' : raw.replace(/\/+$/, '')
    baseURL.value = ''
    configError.value = !url
      ? '未配置 VITE_API_BASE_URL，不会发送请求'
      : !/^https?:\/\/[^\s]+$/i.test(url) && !/^\/(?!\/)[^\s?#]*$/.test(url)
        ? 'VITE_API_BASE_URL 必须是 HTTP(S) 服务根地址或同源代理路径'
        : ''
    if (!configError.value) baseURL.value = url
  }
  function assertCurrent(version: number) {
    if (version !== revision) throw new ApiError('会话已变更，请重新操作')
  }
  function accept(data: TokenResult, version: number, rotation = false) {
    assertCurrent(version)
    if (rotation && user.value && data?.user?.id !== user.value.id) {
      clearSession('账号已在其他页面切换，请重新登录')
      throw new ApiError('登录身份已变更', 40100)
    }
    const remaining = Date.parse(data?.expiresAt ?? '') - Date.now()
    if (
      !data?.user ||
      !data.accessToken ||
      !Number.isFinite(remaining) ||
      remaining <= 0 ||
      data.user.status !== 'active' ||
      !['admin', 'student'].includes(data.user.role) ||
      typeof data.user.id !== 'string'
    ) {
      clearSession()
      throw new ApiError('登录会话无效，请重新登录')
    }
    clearTimeout(timer)
    if (!rotation) {
      revision++
      sessionEpoch.value++
    }
    token.value = data.accessToken
    user.value = data.user
    expiresAt.value = data.expiresAt
    sessionNotice.value = ''
    try {
      ;(remember ? localStorage : sessionStorage).setItem(
        SESSION_KEY,
        JSON.stringify({
          accessToken: data.accessToken,
          expiresAt: data.expiresAt,
          baseURL: baseURL.value,
          remember,
        }),
      )
    } catch {
      sessionNotice.value = '浏览器未允许保存登录状态，关闭页面后需重新登录'
    }
    timer = setTimeout(
      () => {
        void refreshSession().catch(() => {
          if (Date.parse(expiresAt.value) <= Date.now()) clearSession('登录已失效，请重新登录')
        })
      },
      Math.min(Math.max(1000, remaining - 60000), 2147483647),
    )
  }
  async function refreshSession() {
    if (refreshPending) return refreshPending
    const version = revision
    const run = async () => {
      assertCurrent(version)
      const data = await createApiClient({ baseURL: baseURL.value }).post<TokenResult>(
        '/api/v1/auth/refresh',
        {},
        { withCredentials: true, headers: { 'X-Refresh-Intent': '1' }, silent: true },
      )
      accept(data, version, true)
    }
    refreshPending = (async () => {
      try {
        if (typeof navigator !== 'undefined' && navigator.locks)
          await navigator.locks.request('quest-auth-refresh', run)
        else await run()
      } catch (error) {
        if (version === revision && error instanceof ApiError && error.status === 401)
          clearSession('登录已失效，请重新登录')
        throw error
      } finally {
        refreshPending = undefined
      }
    })()
    return refreshPending
  }
  async function ensureAccess() {
    if (Date.parse(expiresAt.value) - Date.now() < 60000) await refreshSession()
  }
  async function restoreSession() {
    if (authenticated.value) return
    if (restorePending) return restorePending
    const version = revision
    const saved = readSession()
    if (
      !saved ||
      !saved.accessToken ||
      saved.baseURL !== baseURL.value ||
      !configured.value ||
      !Number.isFinite(Date.parse(saved.expiresAt))
    ) {
      removeSession()
      return
    }
    remember = saved.remember === true
    restorePending = (async () => {
      try {
        if (Date.parse(saved.expiresAt) - Date.now() < 60000) await refreshSession()
        else {
          const profile = await createApiClient({
            baseURL: baseURL.value,
            getToken: () => saved.accessToken,
          }).get<UserProfile>('/api/v1/auth/me')
          accept(
            { accessToken: saved.accessToken, expiresAt: saved.expiresAt, user: profile },
            version,
          )
        }
      } catch (error) {
        if (version !== revision) return
        const invalid = error instanceof ApiError && (error.status === 401 || error.code === 40100)
        clearSession(invalid ? '登录已失效，请重新登录' : '暂时无法验证登录状态，请重试', invalid)
      } finally {
        restorePending = undefined
      }
    })()
    return restorePending
  }
  async function request<T>(config: ApiRequestConfig): Promise<T> {
    const version = revision
    try {
      if (!configured.value) throw new ApiError(configError.value)
      // 已主动退出/没有身份时拒绝请求，但不将后续组件请求误标为会话过期。
      if (!authenticated.value) throw new ApiError('请先登录', 40100)
      if (Date.parse(expiresAt.value) - Date.now() < 60000) await ensureAccess()
      assertCurrent(version)
      const credential = token.value
      const client = createApiClient({ baseURL: baseURL.value, getToken: () => credential })
      const data = await client.request<T>(config)
      assertCurrent(version)
      return data
    } catch (error) {
      if (version === revision && authenticated.value && error instanceof ApiError) {
        if (error.status === 401 || error.code === 40100) clearSession(errorMessage(error))
        else if (error.code === 40310 && user.value)
          user.value = { ...user.value, mustChangePassword: true }
      }
      if (!config.silent) notifyError(error)
      throw error
    }
  }
  // 流式业务与普通请求共用身份/过期规则，错误由当前页面统一反馈，旧页面不得弹错。
  async function streamRequest<T>(
    operation: (credentials: {
      token: string
      baseURL: string
      assertCurrent: () => void
    }) => Promise<T>,
  ): Promise<T> {
    const version = revision
    try {
      if (!configured.value) throw new ApiError(configError.value)
      if (!authenticated.value) throw new ApiError('请先登录', 40100)
      if (Date.parse(expiresAt.value) - Date.now() < 60000) await ensureAccess()
      assertCurrent(version)
      const result = await operation({
        token: token.value,
        baseURL: baseURL.value,
        assertCurrent: () => assertCurrent(version),
      })
      assertCurrent(version)
      return result
    } catch (error) {
      if (version === revision && authenticated.value && error instanceof ApiError) {
        if (error.status === 401 || error.code === 40100) clearSession(errorMessage(error))
        else if (error.code === 40310 && user.value)
          user.value = { ...user.value, mustChangePassword: true }
      }
      throw error
    }
  }
  async function login(input: LoginInput, options: Pick<ApiRequestConfig, 'silent'> = {}) {
    clearSession()
    if (!configured.value) throw new ApiError(configError.value)
    remember = input.remember === true
    const version = revision
    const data = await createApiClient({ baseURL: baseURL.value }).post<TokenResult>(
      '/api/v1/auth/login',
      input,
      { ...options, withCredentials: true },
    )
    accept(data, version)
  }
  async function loadMe() {
    const version = revision
    const data = await request<UserProfile>({ method: 'GET', url: '/api/v1/auth/me' })
    assertCurrent(version)
    if (
      data.status !== 'active' ||
      !['admin', 'student'].includes(data.role) ||
      typeof data.id !== 'string'
    ) {
      clearSession('账号不可用，请重新登录')
      throw new ApiError('账号不可用', 40100)
    }
    user.value = data
    return data
  }
  async function changePassword(
    input: ChangePasswordInput,
    options: Pick<ApiRequestConfig, 'silent'> = {},
  ) {
    const version = revision
    const data = await request<TokenResult>({
      method: 'POST',
      url: '/api/v1/auth/change-password',
      data: input,
      withCredentials: true,
      ...options,
    })
    accept(data, version)
  }
  async function logout() {
    const credential = token.value
    const stillValid = Date.parse(expiresAt.value) > Date.now()
    const url = baseURL.value
    clearSession()
    if (credential && url)
      await createApiClient({
        baseURL: url,
        ...(stillValid ? { getToken: () => credential } : {}),
      }).post<null>(
        '/api/v1/auth/logout',
        {},
        { withCredentials: true, headers: { 'X-Refresh-Intent': '1' } },
      )
  }
  onScopeDispose(() => clearTimeout(timer))
  return {
    token,
    sessionEpoch,
    user,
    expiresAt,
    baseURL,
    configError,
    configured,
    authenticated,
    sessionNotice,
    features,
    refreshSession,
    loadFeatures: async () => {
      features.value = await request<Record<string, boolean>>({
        method: 'GET',
        url: '/api/v1/auth/features',
      })
    },
    configure,
    clearSession,
    login,
    restoreSession,
    loadMe,
    changePassword,
    logout,
    request,
    streamRequest,
  }
})
