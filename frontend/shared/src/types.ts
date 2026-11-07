export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}
export interface Page<T> {
  list: T[]
  total: number
  page: number
  size: number
}
export type AgentScope = 'admin' | 'student'
/** Canonical wire DTO, matching the phase-one question JSON schema. */
export interface Question {
  id?: string
  schema_version: string
  type: 'single_choice' | 'multiple_choice'
  stem_md: string
  stem_images: string[]
  options: { key: string; text_md: string; image: string | null }[]
  answer: { keys: string[]; source: string; confidence: number | null }
  explanation_md: string
  knowledge_points: string[]
  difficulty: 1 | 2 | 3 | 4 | 5
  source: {
    exam_type: string | null
    exam_name: string | null
    year: number | null
    region: string | null
    paper_id: number | null
    question_no: number | null
  }
  tags: string[]
  status: 'draft' | 'pending_review' | 'published' | 'archived'
  quality: {
    ai_confidence: number | null
    review_status: 'pending' | 'approved' | 'rejected'
    dup_group: string | null
  }
}
export type AgentBlock =
  | { type: 'text'; text: string }
  | { type: 'card'; card: { type: string; payload: unknown } }
  | { type: 'tool'; name: string; status: 'running' | 'done' | 'failed' }
export interface AgentMessage {
  id: string
  role: 'user' | 'assistant'
  blocks: AgentBlock[]
  status: 'streaming' | 'done' | 'error'
}
