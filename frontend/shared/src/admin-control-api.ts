import type { useAuthStore } from './auth'
export interface ModelBalance {
  model: string
  credited: number
  consumed: number
  reserved: number
  available: number
}
export interface UsageRow {
  id: string
  model: string
  capability: string
  reservation: number
  input_tokens: number | null
  output_tokens: number | null
  status: string
  outcome: string
  created_at: string
}
export interface UserControls {
  features: Record<string, boolean>
  models: ModelBalance[]
  usage: UsageRow[]
}
export interface AccountEvent {
  id: number
  actor: number | null
  username: string
  action: string
  outcome: string
  target_id: string | null
  source: string
  user_agent: string
  detail: string
  trace_id: string
  created_at: string
}
export interface StatRow {
  model?: string
  capability?: string
  method?: string
  route?: string
  calls: number
  failed: number
  input_tokens?: number
  output_tokens?: number
  pending_tokens?: number
}
export interface Statistics {
  api: StatRow[]
  ai: StatRow[]
  from: string
  to: string
}
export function createAdminControlApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  const user = (id: string) => `/api/v1/admin/users/${encodeURIComponent(id)}`
  return {
    controls: (id: string) =>
      auth.request<UserControls>({ method: 'GET', url: `${user(id)}/controls` }),
    feature: (id: string, feature: string, enabled: boolean) =>
      auth.request({
        method: 'PATCH',
        url: `${user(id)}/features/${encodeURIComponent(feature)}`,
        data: { enabled },
      }),
    credit: (id: string, data: { amount: number; reason?: string; requestId: string }) =>
      auth.request({ method: 'POST', url: `${user(id)}/credits`, data }),
    kick: (id: string) =>
      auth.request({ method: 'POST', url: `${user(id)}/force-logout`, data: {} }),
    events: (params: { userId: string; kind: string; page: number; size: number }) =>
      auth.request<{ list: AccountEvent[]; total: number }>({
        method: 'GET',
        url: '/api/v1/admin/events',
        params,
      }),
    statistics: (params: { userId: string; from: string; to: string }) =>
      auth.request<Statistics>({ method: 'GET', url: '/api/v1/admin/statistics', params }),
    reconcile: (
      id: string,
      call: string,
      data: { inputTokens: number; outputTokens: number; reason: string },
    ) =>
      auth.request({
        method: 'POST',
        url: `${user(id)}/usage/${encodeURIComponent(call)}/reconcile`,
        data,
      }),
  }
}
