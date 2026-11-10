import type { useAuthStore } from './auth'
import type { StudySubject } from './coach-api'

export interface Material {
  id: string
  sessionId: string
  sourceTitle: string
  sourceDeleted: boolean
  subject: StudySubject
  title: string
  status: 'queued' | 'processing' | 'ready' | 'failed'
  format: 'markdown'
  createdAt: string
  updatedAt: string
  error: string | null
  markdown?: string
}
export interface CreateMaterialInput {
  requestId: string
  instructions?: string
}
export function isMaterial(value: unknown): value is Material {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const item = value as Record<string, unknown>
  return (
    ['id', 'sessionId', 'sourceTitle', 'title', 'createdAt', 'updatedAt'].every(
      (key) => typeof item[key] === 'string',
    ) &&
    typeof item.sourceDeleted === 'boolean' &&
    (item.subject === 'vocational_aptitude' || item.subject === 'administrative_aptitude') &&
    (item.status === 'queued' ||
      item.status === 'processing' ||
      item.status === 'ready' ||
      item.status === 'failed') &&
    item.format === 'markdown' &&
    (item.error === null || typeof item.error === 'string') &&
    (item.markdown === undefined || typeof item.markdown === 'string')
  )
}
export function isActiveMaterial(item: Material) {
  return item.status === 'queued' || item.status === 'processing'
}
export function createMaterialsApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  const root = '/api/v1/study/materials'
  const path = (id: string) => `${root}/${encodeURIComponent(id)}`
  return {
    create: (sessionId: string, data: CreateMaterialInput) =>
      auth.request<Material>({
        method: 'POST',
        url: `/api/v1/coach/sessions/${encodeURIComponent(sessionId)}/materials`,
        data,
      }),
    list: () => auth.request<{ list: Material[] }>({ method: 'GET', url: root }),
    get: (id: string, signal?: AbortSignal) =>
      auth.request<Material>({
        method: 'GET',
        url: path(id),
        ...(signal ? { signal, silent: true } : {}),
      }),
    retry: (id: string, data: { requestId: string }) =>
      auth.request<Material>({ method: 'POST', url: `${path(id)}/retry`, data }),
    download: (id: string) =>
      auth.request<Blob>({ method: 'GET', url: `${path(id)}/download`, responseType: 'blob' }),
  }
}
