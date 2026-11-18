import type { useAuthStore } from './auth'
import {
  isPrivateAsset,
  isPrivateJob,
  type PrivateAsset,
  type PrivateImportJob,
} from './private-questions-api'
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
  attachments?: PrivateAsset[]
  /** 导入任务归属于创建它的AI回复，进度通过会话快照和独立轮询更新。 */
  privateImportId?: string
  /** 用户主动重试失败回复时发送的聊天内容；不会重复提交答案。 */
  retryText?: string
  materialId?: string
  materialNotice?: boolean
}
export interface LearningTopic {
  id: string
  subject: StudySubject
  title: string
  goal: string
  progress: string
  questions: string
  plan: string
  revision: number
  source_session_id: string | null
  updated_at: string
}
export interface LearningTopicInput {
  requestId?: string
  subject: StudySubject
  title: string
  goal: string
  progress: string
  questions: string
  plan: string
  revision: number
}
export interface SessionMemory {
  summary: string
  updatedAt: string
  throughMessageId?: string
}
export interface CoachSession {
  persisted?: boolean
  memory?: SessionMemory | null
  topic?: LearningTopic | null
  capabilities?: Partial<Record<'materials' | 'search' | 'syllabus' | 'coach', boolean>>
  id: string
  subject: StudySubject
  title: string
  examYear?: number | null
  syllabusVersionId?: string | null
  messages: CoachMessage[]
  materials?: Material[]
  privateImports?: PrivateImportJob[]
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
  attachments?: string[]
  draft?: { topicId: string | null }
  requestId: string
  action: 'next' | 'answer' | 'chat'
  text: string
  questionId?: string
  keys?: string[]
}
export function createCoachApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  return {
    topics: () =>
      auth.request<{ list: LearningTopic[] }>({ method: 'GET', url: '/api/v1/coach/topics' }),
    createTopic: (data: LearningTopicInput & { requestId: string }) =>
      auth.request<LearningTopic>({ method: 'POST', url: '/api/v1/coach/topics', data }),
    updateTopic: (id: string, data: LearningTopicInput) =>
      auth.request<LearningTopic>({
        method: 'PUT',
        url: `/api/v1/coach/topics/${encodeURIComponent(id)}`,
        data,
      }),
    deleteTopic: (id: string) =>
      auth.request<null>({
        method: 'DELETE',
        url: `/api/v1/coach/topics/${encodeURIComponent(id)}`,
      }),
    bindTopic: (id: string, topicId: string | null) =>
      auth.request<CoachSession>({
        method: 'PUT',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}/topic`,
        data: { topicId },
      }),
    updateMemory: (id: string, summary: string) =>
      auth.request<CoachSession>({
        method: 'PUT',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}/memory`,
        data: { summary },
      }),
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
    create: (requestId: string, mode: 'practice' | 'chat', topicId?: string) =>
      auth.request<CoachSession>({
        method: 'POST',
        url: '/api/v1/coach/sessions',
        data: {
          requestId,
          mode,
          ...(topicId ? { topicId } : {}),
          ...(mode === 'chat' ? { preview: true } : {}),
        },
      }),
    get: (id: string, silent = false, signal?: AbortSignal) =>
      auth.request<CoachSession>({
        silent,
        method: 'GET',
        url: `/api/v1/coach/sessions/${encodeURIComponent(id)}`,
        ...(signal ? { signal } : {}),
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
    (value.persisted === undefined || typeof value.persisted === 'boolean') &&
    strings(value.suggestedKeys) &&
    (value.materials === undefined ||
      (Array.isArray(value.materials) && value.materials.every(isMaterial))) &&
    (value.privateImports === undefined ||
      (Array.isArray(value.privateImports) && value.privateImports.every(isPrivateJob))) &&
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
        (item.sources == null || isSources(item.sources)) &&
        (item.materialId === undefined ||
          (typeof item.materialId === 'string' &&
            item.materialId.length > 0 &&
            item.materialId.length <= 64)) &&
        (item.materialNotice === undefined || typeof item.materialNotice === 'boolean') &&
        (item.retryText === undefined ||
          (typeof item.retryText === 'string' &&
            item.retryText.trim().length > 0 &&
            item.retryText.length <= 2000)) &&
        (item.privateImportId === undefined ||
          (typeof item.privateImportId === 'string' &&
            item.privateImportId.length > 0 &&
            item.privateImportId.length <= 64)) &&
        (item.attachments === undefined ||
          (Array.isArray(item.attachments) &&
            item.attachments.length <= 4 &&
            item.attachments.every(isPrivateAsset))),
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
