import type { useAuthStore } from './auth'
export interface PublicBank {
  id: string
  name: string
  examType: string
  subject: string
  createdAt: string
}
export interface ImportTask {
  id: string
  bankId: string
  status: 'queued' | 'processing' | 'reviewing' | 'failed'
  errorMessage: string | null
  questionId: string | null
  questionStatus: 'draft' | 'confirmed' | 'published' | null
  createdAt: string
}
export interface PublicDraft {
  type: 'single_choice' | 'multiple_choice'
  stem_md: string
  options: { key: string; text_md: string; image?: string | null }[]
  answer: {
    keys: string[]
    source: 'imported' | 'ai_generated' | 'manual'
    confidence?: number | null
  }
  explanation_md: string
  knowledge_points: string[]
  difficulty: number
  source?: { question_no?: number | null; [key: string]: unknown }
  [key: string]: unknown
}
export interface ImportDocument {
  id: string
  bankId: string
  sourceName: string
  status: 'staged' | 'queued' | 'needs_attention'
  candidateCount: number
  totalCount: number
  queuedCount: number
  processingCount: number
  reviewingCount: number
  failedCount: number
  confirmedCount: number
  publishedCount: number
  deletedCount: number
  report: { warnings?: string[]; page_count?: number }
  createdAt: string
}
export interface PublicQuestionSummary {
  totalCount: number
  draftCount: number
  confirmedCount: number
  publishedCount: number
}
export interface PublicQuestionRow {
  id: string
  questionNo: number | null
  status: 'draft' | 'confirmed' | 'published'
  version: number
  draft: PublicDraft
  updatedAt: string
}
export interface PublicReview {
  id: string
  bankId: string
  taskId: string
  importedBy: string
  status: 'draft' | 'confirmed' | 'published'
  version: number
  sourceText: string
  draft: PublicDraft
  evidence: {
    warnings?: string[]
    provenance?: {
      reference_answer_keys?: string[]
      model_answer_keys?: string[]
      conflicting_model_explanation?: string
      web_search_status?: string
      web_retrieved_at?: string
      web_sources?: { title: string; url: string; excerpt: string }[]
      [key: string]: unknown
    }
  }
  confirmedBy: string | null
  confirmedAt: string | null
}
export function createPublicBankApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  const root = '/api/v1/admin'
  return {
    list: (page = 1, size = 20, keyword = '', examType = '') =>
      auth.request<{ list: PublicBank[]; total: number; page: number; size: number }>({
        method: 'GET',
        url: `${root}/public-banks`,
        params: { page, size, keyword, examType },
      }),
    detail: (id: string) =>
      auth.request<PublicBank>({
        method: 'GET',
        url: `${root}/public-banks/${encodeURIComponent(id)}`,
      }),
    create: (data: { name: string; examType: string; subject: string }) =>
      auth.request<PublicBank>({ method: 'POST', url: `${root}/public-banks`, data }),
    imports: (bankId: string, page = 1, size = 20, source: 'all' | 'text' = 'all') =>
      auth.request<{ list: ImportTask[]; total: number; page: number; size: number }>({
        params: { page, size, source },
        method: 'GET',
        url: `${root}/public-banks/${encodeURIComponent(bankId)}/imports`,
      }),
    questions: (id: string, page = 1, size = 20, keyword = '', status = '') =>
      auth.request<{ list: PublicQuestionRow[]; total: number }>({
        method: 'GET',
        url: `${root}/public-banks/${encodeURIComponent(id)}/questions`,
        params: { page, size, keyword, status },
      }),
    questionSummary: (id: string) =>
      auth.request<PublicQuestionSummary>({
        method: 'GET',
        url: `${root}/public-banks/${encodeURIComponent(id)}/question-summary`,
      }),
    documents: (id: string, page = 1, size = 20) =>
      auth.request<{ list: ImportDocument[]; total: number }>({
        method: 'GET',
        url: `${root}/public-banks/${encodeURIComponent(id)}/documents`,
        params: { page, size },
      }),
    uploadPdf: (id: string, file: File) => {
      const data = new FormData()
      data.append('file', file)
      return auth.request<ImportDocument>({
        method: 'POST',
        url: `${root}/public-banks/${encodeURIComponent(id)}/pdf-imports`,
        data,
        timeout: 45000,
      })
    },
    startDocument: (id: string) =>
      auth.request<ImportDocument>({
        method: 'POST',
        url: `${root}/public-documents/${encodeURIComponent(id)}/start`,
        data: {},
      }),
    publishBatch: (id: string, items: { id: string; version: number }[]) =>
      auth.request<{ publishedCount: number }>({
        method: 'POST',
        url: `${root}/public-banks/${encodeURIComponent(id)}/publish`,
        data: { items },
      }),
    submit: (bankId: string, text: string) =>
      auth.request<ImportTask>({
        method: 'POST',
        url: `${root}/public-banks/${encodeURIComponent(bankId)}/imports`,
        data: { text },
      }),
    importTask: (id: string) =>
      auth.request<ImportTask>({
        method: 'GET',
        url: `${root}/public-imports/${encodeURIComponent(id)}`,
      }),
    withdraw: (id: string, version: number) =>
      auth.request<PublicReview>({
        method: 'POST',
        url: `${root}/public-questions/${encodeURIComponent(id)}/withdraw`,
        data: { version },
      }),
    deleteQuestion: async (id: string, version: number): Promise<void> => {
      await auth.request<null>({
        method: 'DELETE',
        url: `${root}/public-questions/${encodeURIComponent(id)}`,
        data: { version },
      })
    },
    review: (id: string) =>
      auth.request<PublicReview>({
        method: 'GET',
        url: `${root}/public-questions/${encodeURIComponent(id)}`,
      }),
    edit: (id: string, version: number, draft: PublicDraft) =>
      auth.request<PublicReview>({
        method: 'PUT',
        url: `${root}/public-questions/${encodeURIComponent(id)}`,
        data: { version, draft },
      }),
    confirm: (id: string, version: number) =>
      auth.request<PublicReview>({
        method: 'POST',
        url: `${root}/public-questions/${encodeURIComponent(id)}/confirm`,
        data: { version },
      }),
    publish: (id: string, version: number) =>
      auth.request<PublicReview>({
        method: 'POST',
        url: `${root}/public-questions/${encodeURIComponent(id)}/publish`,
        data: { version },
      }),
  }
}
