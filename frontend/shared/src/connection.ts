import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { createApiClient } from './api'
import { notifyError } from './feedback'

export const useConnectionStore = defineStore('connection', () => {
  const baseURL = ref('')
  const configError = ref('未配置 VITE_API_BASE_URL，不会发送请求')
  const token = ref('')
  const health = ref('未检查')
  const systemStatus = ref('未验证登录凭据')
  const systemInfo = ref<unknown>(null)
  const healthBusy = ref(false)
  const systemBusy = ref(false)
  const configured = computed(() => Boolean(baseURL.value) && !configError.value)
  let revision = 0

  function configure(value: string | undefined) {
    const raw = value?.trim() ?? ''
    const url = raw === '/' ? '/' : raw.replace(/\/+$/, '')
    baseURL.value = ''
    revision++
    systemInfo.value = null
    health.value = '未检查'
    systemStatus.value = '未验证登录凭据'
    if (!url) {
      configError.value = '未配置 VITE_API_BASE_URL，不会发送请求'
      return
    }
    if (!/^https?:\/\/[^\s]+$/i.test(url) && !/^\/(?!\/)[^\s?#]*$/.test(url)) {
      configError.value = 'VITE_API_BASE_URL 必须是 HTTP(S) 服务根地址或同源代理路径'
      return
    }
    baseURL.value = url
    configError.value = ''
  }
  function setToken(value: string) {
    token.value = value
      .trim()
      .replace(/^Bearer\s+/i, '')
      .trim()
    revision++
    systemInfo.value = null
    systemStatus.value = '未验证登录凭据'
  }
  function clearToken() {
    setToken('')
  }
  async function checkHealth() {
    if (healthBusy.value) return
    if (!configured.value) {
      health.value = configError.value
      return
    }
    const version = revision
    healthBusy.value = true
    health.value = '正在检查'
    try {
      const response = await fetch(
        `${baseURL.value === '/' ? '' : baseURL.value}/actuator/health`,
        {
          signal: AbortSignal.timeout(10000),
          credentials: 'omit',
          headers: { Accept: 'application/json' },
        },
      )
      const data: unknown = await response.json()
      const status = data && typeof data === 'object' && 'status' in data ? String(data.status) : ''
      if (version === revision) {
        health.value =
          status === 'UP' && response.ok
            ? '服务可用（UP）'
            : `服务未就绪（${status || '未知状态'}，HTTP ${response.status}）`
        if (!response.ok || status !== 'UP')
          notifyError(new Error('Health check failed'), '服务暂未就绪，请稍后重试')
      }
    } catch (error) {
      if (version === revision) {
        health.value = '未能完成检查'
        notifyError(error, '无法连接，请检查 Java 服务、代理与网络')
      }
    } finally {
      healthBusy.value = false
    }
  }
  async function checkSystem() {
    if (systemBusy.value) return
    if (!configured.value) {
      systemStatus.value = configError.value
      return
    }
    if (!token.value) {
      systemStatus.value = '请先输入登录 Token'
      return
    }
    const version = revision
    systemBusy.value = true
    systemInfo.value = null
    systemStatus.value = '正在验证'
    try {
      const client = createApiClient({ baseURL: baseURL.value, getToken: () => token.value })
      const data = await client.get<unknown>('/api/v1/system/info')
      if (version === revision) {
        systemInfo.value = data
        systemStatus.value = '凭据有效，系统信息已获取（不代表已验证角色）'
      }
    } catch (error) {
      if (version === revision) {
        systemStatus.value = '凭据未验证'
        notifyError(error)
      }
    } finally {
      systemBusy.value = false
    }
  }
  return {
    baseURL,
    configError,
    configured,
    token,
    health,
    systemInfo,
    systemStatus,
    healthBusy,
    systemBusy,
    configure,
    setToken,
    clearToken,
    checkHealth,
    checkSystem,
  }
})
