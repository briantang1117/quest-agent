import type { useAuthStore } from './auth'
export type SyllabusExamType = 'career_a' | 'shanghai_civil'
export type SyllabusStatus = 'draft' | 'published' | 'archived'
export type SyllabusJobStatus = 'not_started' | 'queued' | 'processing' | 'ready' | 'failed'
export const SYLLABUS_REVIEW_STATUSES = {
  not_started: 'AI 整理未开始',
  queued: '等待 AI 整理',
  processing: 'AI 整理中',
  ready: 'AI 整理完成',
  failed: 'AI 整理失败',
}
export const SYLLABUS_INDEX_STATUSES = {
  not_started: '索引未开始',
  queued: '等待索引',
  processing: '索引处理中',
  ready: '索引就绪',
  failed: '索引失败',
}
export function syllabusJobActive(status?: SyllabusJobStatus) {
  return status === 'queued' || status === 'processing'
}
export interface SyllabusCitation {
  versionId: string
  title: string
  examYear: number
  sectionTitle: string
  pageStart: number
  pageEnd: number
  text: string
}
export interface StudySyllabus {
  versionId: string | null
  examYear: number
  title?: string
  versionLabel?: string
  sections: SyllabusCitation[]
}
export function createSyllabusStudyApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  return {
    get: (subject: string, examYear: number, keyword = '') =>
      auth.request<StudySyllabus>({
        method: 'GET',
        url: '/api/v1/study/syllabus',
        params: { subject, examYear, keyword },
      }),
  }
}
export interface SyllabusRow {
  id: string
  title: string
  examType: SyllabusExamType
  examYear: number
  versionLabel: string
  status: SyllabusStatus
  // 兼容首版响应；缺省只表示未开始，不推断任务成功。
  reviewStatus?: SyllabusJobStatus
  indexStatus?: SyllabusJobStatus
  reviewError?: string | null
  indexError?: string | null
  indexedCount?: number
  chunkCount?: number
  embeddingModel?: string | null
  embeddingDimensions?: number | null
  revision: number
  pageCount: number
  sectionCount: number
  enabledCount: number
  createdAt: string
}
export interface SyllabusSection {
  ordinal: number
  title: string
  path: string
  kind: 'overview' | 'requirement' | 'example' | 'other'
  scope: 'vocational_aptitude' | 'administrative_aptitude' | 'excluded'
  enabled: boolean
  pageStart: number
  pageEnd: number
  printedPages: string
  text: string
}
export interface SyllabusMetadata {
  title: string
  versionLabel: string
  issuer: string
  publishedDate: string | null
  sourceUrl: string
}
export interface SyllabusDetail extends SyllabusRow, SyllabusMetadata {
  sourceName: string
  sha256: string
  pages: { page: number; printedPage: string | null; text: string }[]
  sections: SyllabusSection[]
  warnings: string[]
  /** 解析器技术提示数量（逐页含图、运行环境限制等），不属于内容问题。 */
  technicalWarningCount?: number
}
export interface SyllabusPage {
  list: SyllabusRow[]
  total: number
  page: number
  size: number
}
export interface SyllabusFilters {
  page: number
  size: number
  keyword: string
  examType: SyllabusExamType | ''
  examYear?: number
  status: SyllabusStatus | ''
}
export interface SyllabusUpdate extends SyllabusMetadata {
  revision: number
  sections: SyllabusSection[]
}
export const SYLLABUS_EXAMS = [
  { value: 'career_a', label: '事业单位 A 类' },
  { value: 'shanghai_civil', label: '上海公务员市考' },
] as const
export const SYLLABUS_STATUSES = { draft: '草稿', published: '已发布', archived: '已归档' }
export function createSyllabusApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  const root = '/api/v1/admin/syllabi'
  const path = (id: string) => `${root}/${encodeURIComponent(id)}`
  return {
    list: (params: SyllabusFilters) =>
      auth.request<SyllabusPage>({ method: 'GET', url: root, params }),
    detail: (id: string) => auth.request<SyllabusDetail>({ method: 'GET', url: path(id) }),
    upload: (
      file: File,
      metadata: SyllabusMetadata & { examType: SyllabusExamType; examYear: number },
    ) => {
      const data = new FormData()
      data.append('file', file)
      for (const [key, value] of Object.entries(metadata)) {
        if (value !== null && value !== '') data.append(key, String(value))
      }
      return auth.request<SyllabusDetail>({ method: 'POST', url: root, data, timeout: 45000 })
    },
    update: (id: string, data: SyllabusUpdate) =>
      auth.request<SyllabusDetail>({ method: 'PUT', url: path(id), data }),
    publish: (id: string, revision: number) =>
      auth.request<SyllabusDetail>({
        method: 'POST',
        url: `${path(id)}/publish`,
        data: { revision },
      }),
    archive: (id: string, revision: number) =>
      auth.request<SyllabusDetail>({
        method: 'POST',
        url: `${path(id)}/archive`,
        data: { revision },
      }),
    retryReview: (id: string, revision: number) =>
      auth.request<SyllabusDetail>({
        method: 'POST',
        url: `${path(id)}/retry-review`,
        data: { revision },
      }),
    rebuildIndex: (id: string, revision: number) =>
      auth.request<SyllabusDetail>({
        method: 'POST',
        url: `${path(id)}/rebuild-index`,
        data: { revision },
      }),
    file: (id: string) =>
      auth.request<Blob>({ method: 'GET', url: `${path(id)}/file`, responseType: 'blob' }),
  }
}
