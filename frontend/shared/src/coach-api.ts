import type { useAuthStore } from './auth'
import { streamPost } from './sse'
import { isMaterial, type Material } from './materials-api'
export type StudySubject = 'vocational_aptitude' | 'administrative_aptitude'
export const STUDY_SUBJECTS = [
  { value: 'vocational_aptitude' as const, label: '事业编 A 类 · 职测' },
  { value: 'administrative_aptitude' as const, label: '上海市考 · 行测' },
]
export function subjectLabel(value: string | null | undefined) {
  return STUDY_SUBJECTS.find((subject) => subject.value === value)?.label ?? '尚未选择科目'
}
export interface CoachQuestion {
  id: string
  type: 'single_choice' | 'multiple_choice'
  stemMd: string
  options: { key: string; textMd: string }[]
}
export interface CoachResult {
  correct: boolean
  selectedKeys: string[]
  answerKeys: string[]
  explanationMd: string
}
/** 快捷建议：文案可来自模型，action 是否可用由 Java 权威状态判定后再下发给前端。 */
export interface CoachSuggestion {
  action: 'next' | 'chat'
  label: string
  text?: string
  required_feature?: 'materials' | 'search' | 'syllabus' | 'coach'
}
export interface CoachSource {
  tool: 'search_web' | 'fetch_page'
  title: string
  url: string
  retrieved_at: string
}
export interface CoachToolStatus {
  tool: 'search_web' | 'fetch_page' | 'get_current_time'
  status: 'running' | 'completed' | 'failed'
}
export interface CoachMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  /** 出题那条消息自带题目快照；追问不会改动它。 */
  question?: CoachQuestion
  /** 判分结果挂在出题消息上，历史题目也能回看答案与正解。 */
  result?: CoachResult
  /** 只出现在最后一条消息上；前端循环渲染，不自造产品动作。 */
  suggestions?: CoachSuggestion[]
  sources?: CoachSource[]
}
export interface CoachSession {
  capabilities?: Partial<Record<'materials' | 'search' | 'syllabus' | 'coach', boolean>>
  id: string
  subject: StudySubject
  title: string
  examYear?: number | null
  syllabusVersionId?: string | null
  messages: CoachMessage[]
  materials?: Material[]
  currentQuestion: CoachQuestion | null
  result: CoachResult | null
  suggestedKeys: string[]
  processing: boolean
}
export interface CoachSessionSummary {
  id: string
  subject: StudySubject
  title: string
  updatedAt: string
}
export interface CoachTurn {
  requestId: string
  action: 'next' | 'answer' | 'chat'
  text: string
  questionId?: string
  keys?: string[]
}
export function createCoachApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  return {
    preference: async () => {
      const value = await auth.request<{ subject: StudySubject | null; examYear?: number | null }>({
        method: 'GET',
        url: '/api/v1/me/study-preference',
      })
      return { ...value, examYear: value.examYear ?? null }
    },
    setPreference: (subject: StudySubject, examYear: number | null = null) =>
      auth.request<{ subject: StudySubject; examYear?: number | null }>({
        method: 'PUT',
        url: '/api/v1/me/study-preference',
        data: { subject, examYear },
      }),
    list: (silent = false) =>
      auth.request<{ list: CoachSessionSummary[] }>({
        silent,
        method: 'GET',
        url: '/api/v1/coach/sessions',
      }),
    create: (requestId: string, mode: 'practice' | 'chat') =>
      auth.request<CoachSession>({
        method: 'POST',
        url: '/api/v1/coach/sessions',
        data: { requestId, mode },
      }),
    get: (id: string, silent = false) =>
      auth.request<CoachSession>({
        silent,
        method: 'GET',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}`,
      }),
    remove: (id: string) =>
      auth.request<null>({
        method: 'DELETE',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}`,
      }),
    turn: (id: string, data: CoachTurn) =>
      auth.request<CoachSession>({
        method: 'POST',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}/turns`,
        data,
        timeout: 110000,
      }),
  }
}

export interface StreamTurnOptions {
  token: string
  baseURL: string
  signal?: AbortSignal
  fetcher?: typeof fetch
  onState: (session: CoachSession) => void
  onDelta: (text: string) => void
  onReset: () => void
  onDone: (session: CoachSession) => void
  onSuggestions?: (items: CoachSuggestion[]) => void
  onToolStatus?: (status: CoachToolStatus) => void
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}
function isQuestion(value: unknown): boolean {
  return (
    object(value) &&
    typeof value.id === 'string' &&
    ['single_choice', 'multiple_choice'].includes(String(value.type)) &&
    typeof value.stemMd === 'string' &&
    Array.isArray(value.options) &&
    value.options.every(
      (item) => object(item) && typeof item.key === 'string' && typeof item.textMd === 'string',
    )
  )
}
function isResult(value: unknown): boolean {
  return (
    object(value) &&
    typeof value.correct === 'boolean' &&
    strings(value.selectedKeys) &&
    strings(value.answerKeys) &&
    typeof value.explanationMd === 'string'
  )
}
function isSuggestions(value: unknown): value is CoachSuggestion[] {
  return (
    Array.isArray(value) &&
    value.every(
      (item) =>
        object(item) &&
        ['next', 'chat'].includes(String(item.action)) &&
        typeof item.label === 'string' &&
        (item.text === undefined || typeof item.text === 'string'),
    )
  )
}
function isSources(value: unknown): value is CoachSource[] {
  return (
    Array.isArray(value) &&
    value.length <= 96 &&
    value.every((item) => {
      if (
        !object(item) ||
        !['search_web', 'fetch_page'].includes(String(item.tool)) ||
        typeof item.title !== 'string' ||
        item.title.length > 200 ||
        typeof item.url !== 'string' ||
        item.url.length > 2048 ||
        typeof item.retrieved_at !== 'string' ||
        !Number.isFinite(Date.parse(item.retrieved_at))
      )
        return false
      try {
        const url = new URL(item.url)
        return (
          ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.hash
        )
      } catch {
        return false
      }
    })
  )
}
function isSession(value: unknown): value is CoachSession {
  return (
    object(value) &&
    typeof value.id === 'string' &&
    typeof value.title === 'string' &&
    STUDY_SUBJECTS.some((item) => item.value === value.subject) &&
    typeof value.processing === 'boolean' &&
    strings(value.suggestedKeys) &&
    (value.materials === undefined ||
      (Array.isArray(value.materials) && value.materials.every(isMaterial))) &&
    (value.currentQuestion === null || isQuestion(value.currentQuestion)) &&
    (value.result === null || isResult(value.result)) &&
    Array.isArray(value.messages) &&
    value.messages.every(
      (item) =>
        object(item) &&
        typeof item.id === 'string' &&
        ['user', 'assistant'].includes(String(item.role)) &&
        typeof item.text === 'string' &&
        (item.question == null || isQuestion(item.question)) &&
        (item.result == null || isResult(item.result)) &&
        (item.suggestions == null || isSuggestions(item.suggestions)) &&
        (item.sources == null || isSources(item.sources)),
    )
  )
}

/** 单次轮次 POST；不重试，不将临时文本合成为权威会话。 */
export async function streamTurn(
  sessionId: string,
  turn: CoachTurn,
  options: StreamTurnOptions,
): Promise<void> {
  const url = `${options.baseURL.replace(/\/$/, '')}/api/v1/coach/sessions/${encodeURIComponent(sessionId)}/turns/stream`
  await streamPost(url, turn, {
    token: options.token,
    signal: options.signal,
    fetcher: options.fetcher,
    onEvent(event) {
      if (!['state', 'delta', 'reset', 'suggestions', 'tool_status', 'done'].includes(event.event))
        return
      const value: unknown = JSON.parse(event.data)
      if (event.event === 'state' || event.event === 'done') {
        if (
          !isSession(value) ||
          value.id !== sessionId ||
          (event.event === 'done' && value.processing)
        )
          throw new Error('无效的陪练会话事件')
        if (event.event === 'state') options.onState(value)
        else options.onDone(value)
      } else if (event.event === 'delta') {
        if (!object(value) || typeof value.text !== 'string') throw new Error('无效的陪练文本事件')
        options.onDelta(value.text)
      } else if (event.event === 'tool_status') {
        if (
          !object(value) ||
          !['search_web', 'fetch_page', 'get_current_time'].includes(String(value.tool)) ||
          !['running', 'completed', 'failed'].includes(String(value.status))
        )
          throw new Error('无效的陪练工具状态')
        options.onToolStatus?.(value as unknown as CoachToolStatus)
      } else if (event.event === 'reset') {
        if (!object(value)) throw new Error('无效的陪练重置事件')
        options.onReset()
      } else {
        if (!object(value) || !isSuggestions(value.items)) throw new Error('无效的陪练建议事件')
        options.onSuggestions?.(value.items)
      }
    },
  })
}
