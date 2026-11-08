import type { useAuthStore } from './auth'
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
}
export interface CoachSession {
  id: string
  subject: StudySubject
  title: string
  messages: CoachMessage[]
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
    preference: () =>
      auth.request<{ subject: StudySubject | null }>({
        method: 'GET',
        url: '/api/v1/me/study-preference',
      }),
    setPreference: (subject: StudySubject) =>
      auth.request<{ subject: StudySubject }>({
        method: 'PUT',
        url: '/api/v1/me/study-preference',
        data: { subject },
      }),
    list: () =>
      auth.request<{ list: CoachSessionSummary[] }>({
        method: 'GET',
        url: '/api/v1/coach/sessions',
      }),
    create: (requestId: string, mode: 'practice' | 'chat') =>
      auth.request<CoachSession>({
        method: 'POST',
        url: '/api/v1/coach/sessions',
        data: { requestId, mode },
      }),
    get: (id: string) =>
      auth.request<CoachSession>({
        method: 'GET',
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
