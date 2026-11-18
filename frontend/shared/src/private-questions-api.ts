import type { useAuthStore } from './auth'
import type { StudySubject } from './coach-api'
export interface PrivateAsset {
  id: string
  filename: string
  kind: 'text' | 'image' | 'pdf'
  mime: string
  sha256: string
}
export interface PrivateCandidate {
  type: 'single_choice' | 'multiple_choice'
  stem_md: string
  options: { key: string; text_md: string }[]
  original_keys: string[]
  original_answer_quote: string
  original_explanation: string
  personal_keys: string[]
  requires_image: boolean
  issues: string[]
  page_numbers: number[]
}
export interface PrivateResult {
  candidate: PrivateCandidate
  answer_keys: string[]
  explanation_md: string
  memory_tip: string
  issues: string[]
  status: 'ready' | 'needs_attention' | 'excluded'
  answer_source: string
  web_verified: boolean
  web_sources: { title: string; url: string; excerpt: string; retrieved_at: string }[]
  source?: {
    session_id: string
    import_id: string
    asset_id: string | null
    filename: string | null
  }
}
export interface PrivateImportItem {
  id: string
  ordinal: number
  status:
    'queued' | 'processing' | 'ready' | 'needs_attention' | 'duplicate' | 'failed' | 'excluded'
  result: PrivateResult | null
  question_id: string | null
  asset_id: string | null
  error_code: number | null
  error_message?: string
}
export interface PrivateImportJob {
  id: string
  session_id: string
  subject: StudySubject
  kind: 'question' | 'mistake'
  status: 'queued' | 'processing' | 'completed' | 'needs_attention' | 'failed' | 'cancelled'
  stage: string
  items: PrivateImportItem[]
  warnings: string[]
  saved: number
  needs_attention: number
  duplicates: number
  failed: number
  source_failures: number
  source_errors?: { id: string; filename: string; code: number; message: string }[]
  error_code: number | null
}
export interface PrivateQuestion {
  id: string
  subject: StudySubject
  kind: 'question' | 'mistake'
  status: 'ready' | 'needs_attention' | 'excluded'
  revision: number
  result: PrivateResult
}
export function createPrivateQuestionsApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  const root = '/api/v1/study'
  return {
    upload: (file: File, requestId: string) => {
      const data = new FormData()
      data.append('requestId', requestId)
      data.append('file', file)
      return auth.request<PrivateAsset>({
        method: 'POST',
        url: '/api/v1/coach/attachments',
        data,
        timeout: 30000,
      })
    },
    file: (id: string) =>
      auth.request<Blob>({
        method: 'GET',
        url: `/api/v1/coach/attachments/${encodeURIComponent(id)}/file`,
        responseType: 'blob',
      }),
    job: (id: string, signal?: AbortSignal) =>
      auth.request<PrivateImportJob>({
        method: 'GET',
        url: `${root}/private-imports/${encodeURIComponent(id)}`,
        signal,
        silent: true,
      }),
    control: (id: string, action: 'retry' | 'cancel') =>
      auth.request<PrivateImportJob>({
        method: 'POST',
        url: `${root}/private-imports/${encodeURIComponent(id)}/actions`,
        data: { action },
      }),
    decide: (
      id: string,
      sessionId: string,
      action: 'use_suggestion' | 'keep_original' | 'exclude',
    ) =>
      auth.request<PrivateImportJob>({
        method: 'POST',
        url: `${root}/private-items/${encodeURIComponent(id)}/decision`,
        data: { sessionId, action },
      }),
    list: (params: { subject: string; status: string; q: string; page: number }) =>
      auth.request<{ list: PrivateQuestion[]; total: number; page: number; size: number }>({
        method: 'GET',
        url: `${root}/private-questions`,
        params,
      }),
  }
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}
export function isPrivateAsset(value: unknown): value is PrivateAsset {
  return (
    object(value) &&
    ['id', 'filename', 'mime', 'sha256'].every((key) => typeof value[key] === 'string') &&
    ['text', 'image', 'pdf'].includes(String(value.kind))
  )
}
export function isPrivateResult(value: unknown): value is PrivateResult {
  if (!object(value) || !object(value.candidate)) return false
  const q = value.candidate
  return (
    ['single_choice', 'multiple_choice'].includes(String(q.type)) &&
    typeof q.stem_md === 'string' &&
    Array.isArray(q.options) &&
    q.options.length >= 2 &&
    q.options.length <= 8 &&
    q.options.every(
      (item) => object(item) && typeof item.key === 'string' && typeof item.text_md === 'string',
    ) &&
    strings(q.original_keys) &&
    strings(q.personal_keys) &&
    strings(q.issues) &&
    Array.isArray(q.page_numbers) &&
    typeof q.requires_image === 'boolean' &&
    strings(value.answer_keys) &&
    strings(value.issues) &&
    typeof value.explanation_md === 'string' &&
    typeof value.memory_tip === 'string' &&
    Array.isArray(value.web_sources) &&
    value.web_sources.every(
      (item) => object(item) && typeof item.url === 'string' && typeof item.title === 'string',
    ) &&
    ['ready', 'needs_attention', 'excluded'].includes(String(value.status))
  )
}
export function isPrivateJob(value: unknown): value is PrivateImportJob {
  if (!object(value)) return false
  return (
    typeof value.id === 'string' &&
    typeof value.session_id === 'string' &&
    ['queued', 'processing', 'completed', 'needs_attention', 'failed', 'cancelled'].includes(
      String(value.status),
    ) &&
    strings(value.warnings) &&
    (value.source_errors === undefined ||
      (Array.isArray(value.source_errors) &&
        value.source_errors.length <= 5 &&
        value.source_errors.every(
          (item) =>
            object(item) &&
            typeof item.id === 'string' &&
            typeof item.filename === 'string' &&
            typeof item.message === 'string' &&
            Number.isInteger(item.code),
        ))) &&
    Array.isArray(value.items) &&
    value.items.length <= 100 &&
    value.items.every(
      (item) =>
        object(item) &&
        typeof item.id === 'string' &&
        typeof item.ordinal === 'number' &&
        [
          'queued',
          'processing',
          'ready',
          'needs_attention',
          'duplicate',
          'failed',
          'excluded',
        ].includes(String(item.status)) &&
        (item.result === null || isPrivateResult(item.result)),
    ) &&
    ['saved', 'needs_attention', 'duplicates', 'failed', 'source_failures'].every(
      (key) => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0,
    )
  )
}
