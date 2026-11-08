import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'
import { notifyError } from './feedback'

export interface ApiRequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

export class ApiError extends Error {
  readonly name = 'ApiError'
  constructor(
    message: string,
    readonly code?: number,
    readonly traceId?: string,
    readonly status?: number,
  ) {
    super(message)
  }
}

function isEnvelope(value: unknown): value is ApiResponse<unknown> {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return (
    typeof v.code === 'number' &&
    typeof v.message === 'string' &&
    typeof v.traceId === 'string' &&
    'data' in v
  )
}

export function createApiClient(options: { baseURL: string; getToken?: () => string }) {
  if (!options.baseURL.trim()) throw new ApiError('请配置 VITE_API_BASE_URL 后再连接服务')
  const transport = axios.create({ baseURL: options.baseURL, timeout: 10000 })
  transport.interceptors.request.use((config) => {
    const token = options.getToken?.()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    return config
  })
  async function request<T>(config: ApiRequestConfig): Promise<T> {
    try {
      const response = await transport.request<unknown>(config)
      if (!isEnvelope(response.data))
        throw new ApiError('服务响应格式不符合约定', undefined, undefined, response.status)
      const envelope = response.data
      if (envelope.code !== 0)
        throw new ApiError(envelope.message, envelope.code, envelope.traceId, response.status)
      return envelope.data as T
    } catch (error) {
      if (axios.isCancel(error)) throw error
      let failure: ApiError
      if (error instanceof ApiError) failure = error
      else if (axios.isAxiosError(error) && error.response) {
        const data = error.response.data as Partial<ApiResponse<unknown>> | undefined
        failure = new ApiError(
          typeof data?.message === 'string'
            ? data.message
            : `服务请求失败（HTTP ${error.response.status}）`,
          typeof data?.code === 'number' ? data.code : undefined,
          typeof data?.traceId === 'string' ? data.traceId : undefined,
          error.response.status,
        )
      } else failure = new ApiError('网络连接失败，请检查服务地址、代理及网络')
      if (!config.silent) notifyError(failure)
      throw failure
    }
  }
  return {
    defaults: transport.defaults,
    request,
    get: <T>(url: string, config?: ApiRequestConfig) =>
      request<T>({ ...config, method: 'GET', url }),
    post: <T>(url: string, data: unknown, config?: ApiRequestConfig) =>
      request<T>({ ...config, method: 'POST', url, data }),
  }
}
