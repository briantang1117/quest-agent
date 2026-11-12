import type { useAuthStore } from './auth'
export interface AiCallRow {
  id: string
  action: string
  outcome: string
  actor: string | null
  targetId: string | null
  traceId: string | null
  elapsedMs: number
  attempts: number
  model: string
  error: string
  createdAt: string
}
export interface AiCallPage {
  list: AiCallRow[]
  total: number
  page: number
  size: number
}
export interface AiCallDetail {
  row: AiCallRow
  requestJson: string
  responseText: string | null
}
export const AI_ACTIONS = [
  { value: 'ai.tool_time', label: '本地时间查询' },
  { value: 'ai.coach_workflow', label: '陪练工作流' },
  { value: 'ai.coach_turn', label: '陪练对话' },
  { value: 'ai.material_generate', label: '学习资料整理' },
  { value: 'ai.public_import', label: '题目解析' },
  { value: 'ai.pdf_inspect', label: 'PDF 预检' },
]
export function actionLabel(value: string) {
  return AI_ACTIONS.find((item) => item.value === value)?.label ?? value
}
/** 审计详情里的原文可能很长，前端折叠展示，不截断数据本身。 */
export function prettyJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
/** 空筛选不下发，避免后端把空串当成"只匹配空值"。 */
export function aiAuditParams(
  filters: { action?: string; outcome?: string },
  page: number,
  size: number,
) {
  return {
    page,
    size,
    action: filters.action || undefined,
    outcome: filters.outcome || undefined,
  }
}
export function createAiAuditApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  return {
    list: (params: { page: number; size: number; action?: string; outcome?: string }) =>
      auth.request<AiCallPage>({ method: 'GET', url: '/api/v1/admin/ai-calls', params }),
    detail: (id: string) =>
      auth.request<AiCallDetail>({
        method: 'GET',
        url: `/api/v1/admin/ai-calls/${encodeURIComponent(id)}`,
      }),
  }
}
