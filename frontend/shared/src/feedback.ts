import { ApiError } from './api'

export interface ErrorFeedback {
  kind: 'message' | 'dialog'
  message: string
  title?: string
}
export type ErrorFeedbackRules = Readonly<Record<number, Partial<ErrorFeedback>>>
export type ErrorFeedbackAdapter = (feedback: ErrorFeedback) => void

const messages: Readonly<Record<number, string>> = {
  40000: '提交内容不符合要求，请检查后重试',
  40100: '登录已失效，请重新登录',
  40300: '暂无权限执行此操作，请联系管理员',
  40310: '请先修改密码后再继续操作',
  40400: '请求的内容不存在或已被移除',
  40420: '公共题库内容不存在或已被移除',
  40900: '用户名已存在，请更换后重试',
  40920: '内容已变更或当前状态不允许此操作，请刷新后重试',
  42900: '操作过于频繁，请稍后再试',
  50320: '公共题库暂时不可用，请联系管理员',
  50321: 'AI服务暂未配置，请联系管理员',
  50322: 'AI处理失败，请检查任务状态后再操作',
}
let adapter: ErrorFeedbackAdapter | undefined
let rules: ErrorFeedbackRules = {}
const notified = new WeakSet<object>()

export function installErrorFeedback(
  next: ErrorFeedbackAdapter,
  nextRules: ErrorFeedbackRules = {},
) {
  adapter = next
  rules = nextRules
  return () => {
    if (adapter === next) {
      adapter = undefined
      rules = {}
    }
  }
}

export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '请求失败，请稍后重试'
  if (error.code !== undefined && messages[error.code]) return messages[error.code]!
  if (error.status === 401) return messages[40100]!
  if (error.status === 403) return messages[40300]!
  if (error.status === 404) return messages[40400]!
  if (error.status === 429) return messages[42900]!
  if (error.status && error.status >= 500) return '服务暂时不可用，请稍后重试'
  if (error.message === '网络连接失败，请检查服务地址、代理及网络') return error.message
  return '请求失败，请稍后重试'
}

export function isErrorNotified(error: unknown): boolean {
  return typeof error === 'object' && error !== null && notified.has(error)
}

export function isRequestCanceled(error: unknown): boolean {
  return error instanceof Error && ['CanceledError', 'AbortError'].includes(error.name)
}

// fallback仅接受调用方提供的固定文案，不直接展示服务端message或traceId。
export function notifyError(error: unknown, fallback?: string): void {
  if (!adapter || isErrorNotified(error) || isRequestCanceled(error)) return
  const rule = error instanceof ApiError && error.code !== undefined ? rules[error.code] : undefined
  if (typeof error === 'object' && error !== null) notified.add(error)
  adapter({
    kind: rule?.kind ?? 'message',
    message: rule?.message ?? fallback ?? errorMessage(error),
    title: rule?.title,
  })
}
