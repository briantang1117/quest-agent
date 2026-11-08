import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { defineComponent, h, KeepAlive, ref } from 'vue'
import ArcoVue from '@arco-design/web-vue'
import { createPublicBankApi, installErrorFeedback, useAuthStore } from '@quest/shared'
import { createAppRouter } from './router'
import AdminBanksView from './views/AdminBanksView.vue'
import AdminQuestionView from './views/AdminQuestionView.vue'
import AdminBankDetailView from './views/AdminBankDetailView.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.useRealTimers()
})
async function render(
  component: typeof AdminBanksView,
  path: string,
  responder: (config: { url?: string; method?: string; data?: unknown }) => unknown,
  keepAlive = false,
  realTeleport = false,
  summaryResponder: () => unknown = questionSummaryFixture,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  auth.configure('/gateway')
  auth.token = 'test-token'
  auth.expiresAt = new Date(Date.now() + 3600000).toISOString()
  vi.spyOn(auth, 'restoreSession').mockResolvedValue()
  auth.user = {
    id: '1',
    username: 'admin',
    displayName: '管理员',
    role: 'admin',
    status: 'active',
    mustChangePassword: false,
    createdAt: '2026-11-08T00:00:00Z',
    lastLoginAt: null,
  }
  const request = vi.spyOn(auth, 'request').mockImplementation(async (config) => {
    if (config.url?.endsWith('/question-summary')) return summaryResponder() as never
    return responder(config) as never
  })
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push(path)
  await router.isReady()
  const active = ref(true)
  const host = defineComponent({
    setup: () => () => h(KeepAlive, null, { default: () => (active.value ? h(component) : null) }),
  })
  const wrapper = mount(keepAlive ? host : component, {
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: !realTeleport } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, request, router, active }
}
it('新建题库使用标准弹窗底部按钮，校验失败或请求失败不关闭', async () => {
  let rejectCreate!: (reason: Error) => void
  const pending = new Promise((_, reject) => {
    rejectCreate = reject
  })
  const { wrapper, request } = await render(AdminBanksView, '/admin/banks', (c) =>
    c.method === 'POST' ? pending : { list: [], total: 0, page: 1, size: 20 },
  )
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '新建题库')!
    .trigger('click')
  await flushPromises()
  const dialog = () =>
    wrapper.findAll('.arco-modal').find((node) => node.text().includes('新建公共题库'))!
  const createButton = () => dialog().get('.arco-modal-footer .arco-btn-primary')
  expect(dialog().get('.arco-modal-footer').text()).toContain('取消')
  expect(createButton().text()).toBe('创建')
  expect(dialog().find('.arco-modal-body button.arco-btn').exists()).toBe(false)
  await createButton().trigger('click')
  await flushPromises()
  expect(request.mock.calls.some(([config]) => config.method === 'POST')).toBe(false)
  expect(dialog().text()).toContain('请输入题库名称')
  await dialog().get('input.arco-input').setValue('公共职测')
  await createButton().trigger('click')
  await flushPromises()
  expect(createButton().classes()).toContain('arco-btn-loading')
  rejectCreate(new Error('创建失败'))
  await flushPromises()
  expect(dialog().isVisible()).toBe(true)
  expect(dialog().get('input.arco-input').element).toHaveProperty('value', '公共职测')
})

async function openManual(wrapper: ReturnType<typeof mount>) {
  const dropdown = wrapper.findComponent({ name: 'DropdownButton' })
  expect(dropdown.exists()).toBe(true)
  dropdown.vm.$emit('select', 'manual')
  await flushPromises()
  return wrapper.findAll('.arco-modal').find((node) => node.text().includes('单题录入'))!
}
it('单题文本解析使用标准底部操作区，提交失败保留输入且不自动重试', async () => {
  let rejectImport!: (reason: Error) => void
  const pending = new Promise((_, reject) => {
    rejectImport = reject
  })
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) =>
    c.method === 'POST' ? pending : bankResponder()(c),
  )
  const dialog = await openManual(wrapper)
  const submit = () => dialog.get('.arco-modal-footer .arco-btn-primary')
  expect(submit().text()).toBe('解析并录入')
  expect(submit().attributes('disabled')).toBeDefined()
  expect(dialog.text()).toContain('模型')
  await dialog.get('textarea').setValue('  合成题干 A.甲 B.乙  ')
  await submit().trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: `/api/v1/admin/public-banks/${bankId}/imports`,
      data: { text: '合成题干 A.甲 B.乙' },
    }),
  )
  expect(dialog.get('textarea').attributes('disabled')).toBeDefined()
  expect(dialog.get('.arco-modal-footer .arco-btn-secondary').attributes('disabled')).toBeDefined()
  rejectImport(new Error('提交失败'))
  await flushPromises()
  expect(dialog.isVisible()).toBe(true)
  expect(dialog.get('textarea').element).toHaveProperty('value', '  合成题干 A.甲 B.乙  ')
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
})

it('管理题库跳转独立维护页，列表不再内嵌任务', async () => {
  const id = '11111111-1111-1111-1111-111111111111'
  const { wrapper, router, request } = await render(AdminBanksView, '/admin/banks', () => ({
    list: [{ id, name: '职测', examType: 'career_a', createdAt: '2026-11-08T00:00:00Z' }],
    total: 1,
    page: 1,
    size: 20,
  }))
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '管理题库')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe(`/admin/banks/${id}`)
  expect(wrapper.text()).not.toContain('导入单题文本')
  expect(request.mock.calls.some(([c]) => c.url?.endsWith('/imports'))).toBe(false)
})

it('题库维护页缓存离开后不会把审核题目ID误当题库ID请求', async () => {
  const bankId = '11111111-1111-1111-1111-111111111111'
  const { router, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) =>
    c.url?.endsWith('/imports') || c.url?.endsWith('/documents') || c.url?.endsWith('/questions')
      ? { list: [], total: 0, page: 1, size: 20 }
      : { id: bankId, name: '职测', examType: 'career_a' },
  )
  request.mockClear()
  await router.push('/admin/questions/22222222-2222-2222-2222-222222222222')
  await flushPromises()
  expect(request).not.toHaveBeenCalled()
})

it('题库维护显示真实题目内容并提供PDF批量上传入口', async () => {
  const id = '11111111-1111-1111-1111-111111111111'
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${id}`, (c) =>
    c.url?.endsWith('/questions')
      ? {
          list: [
            { id: 'q1', status: 'draft', version: 1, draft: { stem_md: '批量导入的合成题目' } },
          ],
          total: 1,
        }
      : c.url?.endsWith('/imports') || c.url?.endsWith('/documents')
        ? { list: [], total: 0 }
        : { id, name: '职测', examType: 'career_a' },
  )
  expect(wrapper.text()).toContain('批量导入的合成题目')
  expect(wrapper.text()).toContain('从 PDF 导入')
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ url: `/api/v1/admin/public-banks/${id}/questions` }),
  )
})

it('PDF使用Arco拖拽选文件且只在底部确认后上传，移除后不可提交', async () => {
  const id = '11111111-1111-1111-1111-111111111111'
  let rejectUpload!: (reason: Error) => void
  const pending = new Promise((_, reject) => {
    rejectUpload = reject
  })
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${id}`, (c) =>
    c.method === 'POST'
      ? pending
      : c.url?.endsWith(id)
        ? { id, name: '职测', examType: 'career_a' }
        : { list: [], total: 0 },
  )
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '从 PDF 导入')!
    .trigger('click')
  await flushPromises()
  const upload = wrapper.findComponent({ name: 'Upload' })
  expect(upload.exists()).toBe(true)
  const dialog = () =>
    wrapper.findAll('.arco-modal').find((node) => node.text().includes('批量导入 PDF'))!
  const confirm = () => dialog().get('.arco-modal-footer .arco-btn-primary')
  const file = new File(['%PDF-synthetic'], '合成试卷.pdf', { type: 'application/pdf' })
  const input = upload.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
  expect(upload.text()).toContain('合成试卷.pdf')
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  expect(confirm().attributes('disabled')).toBeUndefined()
  upload.vm.$emit('update:fileList', [])
  await flushPromises()
  expect(confirm().attributes('disabled')).toBeDefined()
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
  await confirm().trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: `/api/v1/admin/public-banks/${id}/pdf-imports`,
      method: 'POST',
      data: expect.any(FormData),
    }),
  )
  const data = request.mock.lastCall?.[0].data as FormData
  expect(data.get('file')).toBe(file)
  rejectUpload(new Error('上传失败'))
  await flushPromises()
  expect(dialog().isVisible()).toBe(true)
  expect(upload.text()).toContain('合成试卷.pdf')
})

function reviewFixture(id = 'q1') {
  return {
    id,
    bankId: 'b1',
    taskId: 't1',
    importedBy: '1',
    status: 'draft',
    version: 1,
    sourceText: `原材料-${id}`,
    draft: {
      type: 'single_choice',
      stem_md: `题干-${id}`,
      options: [
        { key: 'A', text_md: '甲' },
        { key: 'B', text_md: '乙' },
      ],
      answer: { keys: ['A'], source: 'ai_generated' },
      explanation_md: '解析',
      knowledge_points: ['GK'],
      difficulty: 3,
    },
    evidence: {
      warnings: [],
      provenance: {
        web_sources: [],
        reference_answer_keys: ['B'],
        model_answer_keys: ['A'],
        conflicting_model_explanation: '针对A的冲突解析',
      },
    },
  }
}
const bankId = '11111111-1111-1111-1111-111111111111'
const questionId = '22222222-2222-2222-2222-222222222222'
function questionSummaryFixture() {
  return { totalCount: 123, draftCount: 70, confirmedCount: 30, publishedCount: 23 }
}
async function openDocuments(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('[data-test="open-documents"]').trigger('click')
  await flushPromises()
  return wrapper.get('.arco-drawer')
}

it('题目查询API透传分页与筛选，汇总API独立于筛选且编码题库ID', async () => {
  const request = vi.fn().mockResolvedValue(null)
  const api = createPublicBankApi({ request } as unknown as Parameters<
    typeof createPublicBankApi
  >[0])
  await api.questions('b/1', 2, 50, '题名', 'confirmed')
  await api.questions('b/1')
  await api.questionSummary('b/1')
  expect(request.mock.calls.map(([config]) => config)).toEqual([
    {
      method: 'GET',
      url: '/api/v1/admin/public-banks/b%2F1/questions',
      params: { page: 2, size: 50, keyword: '题名', status: 'confirmed' },
    },
    {
      method: 'GET',
      url: '/api/v1/admin/public-banks/b%2F1/questions',
      params: { page: 1, size: 20, keyword: '', status: '' },
    },
    { method: 'GET', url: '/api/v1/admin/public-banks/b%2F1/question-summary' },
  ])
})
it('试卷头显示独立汇总和北京时间，工具栏同排且PDF批次仅在抽屉内展示', async () => {
  const { wrapper, request } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder([documentFixture()]),
  )
  const header = wrapper.get('[data-test="bank-header"]')
  expect(wrapper.get('.arco-card-header').text()).toContain('真题试卷')
  expect(wrapper.get('.arco-card-header').text()).not.toContain('题目总数')
  expect(wrapper.get('.arco-card-header').text()).not.toContain('创建时间')
  expect(wrapper.get('.arco-card-body').find('[data-test="bank-header"]').exists()).toBe(true)
  expect(header.text()).toContain('职测')
  expect(header.text()).toContain('2026/11/08 08:00')
  expect(header.text()).toContain('题目总数 123')
  expect(header.text()).toContain('未确认 70')
  expect(header.text()).toContain('已确认 30')
  expect(header.text()).toContain('已发布 23')
  expect(wrapper.findAll('h3').some((node) => node.text() === '题库内容')).toBe(false)
  const toolbar = wrapper.get('[data-test="question-toolbar"]')
  expect(toolbar.findComponent({ name: 'DropdownButton' }).exists()).toBe(true)
  expect(toolbar.find('[data-test="batch-publish"]').exists()).toBe(true)
  expect(toolbar.find('[data-test="open-documents"]').exists()).toBe(true)
  expect(toolbar.find('[data-test="refresh-content"]').exists()).toBe(false)
  expect(wrapper.get('.arco-card-body').findAllComponents({ name: 'Table' })).toHaveLength(1)
  expect(wrapper.text()).not.toContain('试卷.pdf')
  request.mockClear()
  const drawer = await openDocuments(wrapper)
  expect(drawer.text()).toContain('试卷.pdf')
  const component = wrapper.findComponent({ name: 'Drawer' })
  expect(component.props('placement')).toBe('right')
  expect(component.props('width')).toBe('min(1000px, 100vw)')
  expect(request).not.toHaveBeenCalled()
  wrapper.findAllComponents({ name: 'Pagination' })[1]!.vm.$emit('change', 2)
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: `/api/v1/admin/public-banks/${bankId}/documents`,
      params: { page: 2, size: 20 },
    }),
  )
})
it('题目筛选提交与输入分开，查询清空页码和选择，翻页和页大小保留条件且不客户端排序', async () => {
  const { wrapper, request } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder(
      [],
      [],
      [
        { id: 'q2', questionNo: 12, version: 3, status: 'confirmed', draft: { stem_md: '后题号' } },
        { id: 'q1', questionNo: 1, version: 2, status: 'confirmed', draft: { stem_md: '前题号' } },
      ],
    ),
  )
  const table = wrapper.findComponent({ name: 'Table' })
  const pagination = wrapper.findComponent({ name: 'Pagination' })
  expect(pagination.props('pageSizeOptions')).toEqual([20, 50, 100])
  expect(table.props('data').map((row: { id: string }) => row.id)).toEqual(['q2', 'q1'])
  expect(table.props('rowSelection').onlyCurrent).toBe(true)
  await table.get('tbody input[type="checkbox"]').setValue(true)
  pagination.vm.$emit('change', 2)
  await flushPromises()
  await table.get('tbody input[type="checkbox"]').setValue(true)
  request.mockClear()
  await wrapper.get('#question-keyword').setValue('  合成题  ')
  wrapper.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'confirmed')
  await flushPromises()
  expect(request).not.toHaveBeenCalled()
  await wrapper.get('[data-test="question-filter"]').trigger('submit')
  await flushPromises()
  const lastQuestionParams = () =>
    request.mock.calls.filter(([c]) => c.url?.endsWith('/questions')).at(-1)?.[0].params
  expect(lastQuestionParams()).toEqual({
    page: 1,
    size: 20,
    keyword: '合成题',
    status: 'confirmed',
  })
  expect(table.props('selectedKeys')).toEqual([])
  expect(wrapper.get('[data-test="bank-header"]').text()).toContain('题目总数 123')
  await wrapper.get('#question-keyword').setValue('尚未提交的新输入')
  pagination.vm.$emit('change', 3)
  await flushPromises()
  expect(lastQuestionParams()).toEqual({
    page: 3,
    size: 20,
    keyword: '合成题',
    status: 'confirmed',
  })
  pagination.vm.$emit('pageSizeChange', 50)
  await flushPromises()
  expect(lastQuestionParams()).toEqual({
    page: 1,
    size: 50,
    keyword: '合成题',
    status: 'confirmed',
  })
  await table.get('tbody input[type="checkbox"]').setValue(true)
  await wrapper.get('[data-test="reset-question-filters"]').trigger('click')
  await flushPromises()
  expect(lastQuestionParams()).toEqual({ page: 1, size: 50, keyword: '', status: '' })
  expect(table.props('selectedKeys')).toEqual([])
  expect(wrapper.get('#question-keyword').element).toHaveProperty('value', '')
})
it('刷新和返页保留未提交筛选输入，仅请求已提交条件，刷新版本变化清除勾选', async () => {
  let version = 2
  let summaryTotal = 123
  const { wrapper, request, router, active } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    (config) =>
      bankResponder(
        [],
        [],
        [{ id: 'q1', questionNo: 1, version, status: 'confirmed', draft: { stem_md: '题目' } }],
      )(config),
    true,
    false,
    () => ({ ...questionSummaryFixture(), totalCount: summaryTotal }),
  )
  await wrapper.get('#question-keyword').setValue('已提交')
  await wrapper.get('[data-test="question-filter"]').trigger('submit')
  await flushPromises()
  wrapper.findComponent({ name: 'Pagination' }).vm.$emit('change', 2)
  await flushPromises()
  const table = wrapper.findComponent({ name: 'Table' })
  await table.get('tbody input[type="checkbox"]').setValue(true)
  await wrapper.get('#question-keyword').setValue('未提交')
  version = 3
  summaryTotal = 124
  request.mockClear()
  await wrapper.get('[data-test="question-filter"]').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(3)
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: `/api/v1/admin/public-banks/${bankId}/questions`,
      params: { page: 1, size: 20, keyword: '未提交', status: '' },
    }),
  )
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ url: `/api/v1/admin/public-banks/${bankId}/question-summary` }),
  )
  expect(table.props('selectedKeys')).toEqual([])
  expect(wrapper.get('[data-test="bank-header"]').text()).toContain('题目总数 124')
  expect(wrapper.get('#question-keyword').element).toHaveProperty('value', '未提交')
  active.value = false
  await router.push(`/admin/questions/${questionId}`)
  await flushPromises()
  request.mockClear()
  expect(request).not.toHaveBeenCalled()
  await router.push(`/admin/banks/${bankId}`)
  active.value = true
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(3)
  expect(wrapper.get('#question-keyword').element).toHaveProperty('value', '未提交')
})
it.each(['question-summary', 'questions', 'documents'])(
  '刷新%s失败使用全局错误通知且保留现有内容',
  async (endpoint) => {
    let fail = false
    const feedback = vi.fn()
    const cleanup = installErrorFeedback(feedback)
    try {
      const { wrapper } = await render(
        AdminBankDetailView,
        `/admin/banks/${bankId}`,
        (config) => {
          if (fail && config.url?.endsWith(`/${endpoint}`)) throw new Error('读取失败')
          return bankResponder(
            [],
            [],
            [{ id: 'q1', status: 'draft', version: 1, draft: { stem_md: '保留的题目' } }],
          )(config)
        },
        false,
        false,
        () => {
          if (fail && endpoint === 'question-summary') throw new Error('汇总失败')
          return questionSummaryFixture()
        },
      )
      fail = true
      await wrapper.get('[data-test="question-filter"]').trigger('submit')
      await flushPromises()
      expect(feedback).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).toContain('保留的题目')
      expect(
        wrapper
          .get('[data-test="question-filter"]')
          .get('button[type="submit"]')
          .attributes('disabled'),
      ).toBeUndefined()
      expect(wrapper.find('[data-test="refresh-content"]').exists()).toBe(false)
    } finally {
      cleanup()
    }
  },
)
function documentFixture(overrides = {}) {
  return {
    id: 'd1',
    bankId,
    sourceName: '试卷.pdf',
    status: 'queued',
    candidateCount: 2,
    totalCount: 2,
    queuedCount: 0,
    processingCount: 0,
    reviewingCount: 2,
    failedCount: 0,
    confirmedCount: 0,
    publishedCount: 0,
    deletedCount: 0,
    report: {},
    createdAt: '2026-11-08T00:00:00Z',
    ...overrides,
  }
}
function bankResponder(
  documents: unknown[] = [],
  tasks: unknown[] = [],
  questions: unknown[] = [],
) {
  return (c: { url?: string }) =>
    c.url?.endsWith('/documents')
      ? { list: documents, total: documents.length }
      : c.url?.endsWith('/imports')
        ? { list: tasks, total: tasks.length }
        : c.url?.endsWith('/questions')
          ? { list: questions, total: questions.length }
          : {
              id: bankId,
              name: '真题试卷',
              examType: 'career_a',
              subject: 'vocational_aptitude',
              createdAt: '2026-11-08T00:00:00Z',
            }
}
it('只勾选当前页已确认题目，确认后带版本批量发布并刷新清空选择', async () => {
  let published = false
  const questions = [
    { id: 'q1', version: 2, status: 'confirmed', draft: { stem_md: '已审核题' } },
    { id: 'q2', version: 1, status: 'draft', draft: { stem_md: '未审核题' } },
    { id: 'q3', version: 1, status: 'published', draft: { stem_md: '已发布题' } },
  ]
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') {
      published = true
      return { publishedCount: 1 }
    }
    return bankResponder(
      [],
      [],
      questions.map((q) => (published && q.id === 'q1' ? { ...q, status: 'published' } : q)),
    )(c)
  })
  const table = wrapper.findAllComponents({ name: 'Table' })[0]!
  expect(table.props('rowSelection')).toBeTruthy()
  const checks = table.findAll('tbody input[type="checkbox"]')
  expect(checks).toHaveLength(3)
  expect(checks[1]!.attributes('disabled')).toBeDefined()
  expect(checks[2]!.attributes('disabled')).toBeDefined()
  const publishButton = () => wrapper.get('[data-test="batch-publish"]')
  expect(publishButton().attributes('disabled')).toBeDefined()
  await checks[0]!.setValue(true)
  expect(publishButton().attributes('disabled')).toBeUndefined()
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  const confirmation = wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.find('[data-test="batch-publish"]').exists())!
  confirmation.vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: `/api/v1/admin/public-banks/${bankId}/publish`,
      data: { items: [{ id: 'q1', version: 2 }] },
    }),
  )
  expect(publishButton().attributes('disabled')).toBeDefined()
  expect(table.props('selectedKeys')).toEqual([])
})
it('批量发布失败只请求一次并刷新状态，翻页清空已选题目', async () => {
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') throw new Error('conflict')
    return bankResponder(
      [],
      [],
      [{ id: 'q1', version: 3, status: 'confirmed', draft: { stem_md: '已审核' } }],
    )(c)
  })
  const table = wrapper.findAllComponents({ name: 'Table' })[0]!
  await table.get('tbody input[type="checkbox"]').setValue(true)
  const publish = wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.find('[data-test="batch-publish"]').exists())!
  publish.vm.$emit('ok')
  await flushPromises()
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
  expect(table.props('selectedKeys')).toEqual([])
  await table.get('tbody input[type="checkbox"]').setValue(true)
  const pagination = wrapper.findAllComponents({ name: 'Pagination' })[0]!
  pagination.vm.$emit('change', 2)
  await flushPromises()
  expect(table.props('selectedKeys')).toEqual([])
})
it('题号列使用响应questionNo，空题号显示破折号而不猜草稿题号', async () => {
  const { wrapper } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder(
      [],
      [],
      [
        { id: 'q1', questionNo: 12, status: 'draft', draft: { stem_md: '第十二题' } },
        {
          id: 'q2',
          questionNo: null,
          status: 'draft',
          draft: { stem_md: '无题号', source: { question_no: '不应猜测' } },
        },
      ],
    ),
  )
  const table = wrapper.findAllComponents({ name: 'Table' })[0]!
  expect(table.text()).toContain('题号')
  const rows = table.findAll('tbody tr')
  expect(rows[0]!.findAll('td')[1]!.text()).toBe('12')
  expect(rows[1]!.findAll('td')[1]!.text()).toBe('—')
})
it.each([
  [{ reviewingCount: 2 }, '未确认'],
  [{ reviewingCount: 1, failedCount: 1 }, '部分失败'],
  [{ reviewingCount: 0, failedCount: 2 }, '失败'],
  [{ reviewingCount: 1, processingCount: 1 }, '处理中'],
])('queued批次根据真实任务计数展示完成状态 %j', async (counts, label) => {
  const { wrapper } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder([documentFixture(counts)]),
  )
  await openDocuments(wrapper)
  const table = wrapper.findAllComponents({ name: 'Table' })[1]!
  expect(table.text()).toContain(label)
  expect(table.text()).not.toContain('已加入生成队列')
})
it.each([
  [{ reviewingCount: 0, confirmedCount: 2 }, '已确认'],
  [{ reviewingCount: 0, confirmedCount: 1, publishedCount: 1 }, '部分发布'],
  [{ reviewingCount: 0, confirmedCount: 0, publishedCount: 2 }, '已发布'],
])('PDF批次审核发布进度由题目状态决定 %j', async (counts, label) => {
  const { wrapper, request } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder([documentFixture(counts)]),
  )
  await openDocuments(wrapper)
  const batchTable = wrapper.findAllComponents({ name: 'Table' })[1]!
  expect(batchTable.text()).toContain(label)
  expect(batchTable.text()).toContain('审核进度')
  expect(request.mock.calls.some(([c]) => c.url?.endsWith('/imports'))).toBe(false)
})
it('题库只展示统一内容列表和PDF批次，已删除批次不误标未确认', async () => {
  const { wrapper, request } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder([documentFixture({ reviewingCount: 0, deletedCount: 2 })]),
  )
  expect(wrapper.findAllComponents({ name: 'Table' })).toHaveLength(1)
  expect(wrapper.findAllComponents({ name: 'Pagination' })).toHaveLength(1)
  await openDocuments(wrapper)
  expect(wrapper.findAllComponents({ name: 'Table' })).toHaveLength(2)
  expect(wrapper.findAllComponents({ name: 'Pagination' })).toHaveLength(2)
  expect(wrapper.text()).not.toContain('单题文本导入记录')
  expect(request.mock.calls.some(([c]) => c.url?.endsWith('/imports'))).toBe(false)
  const batch = wrapper.findAllComponents({ name: 'Table' })[1]!
  expect(batch.text()).toContain('已删除 2')
  expect(batch.findAll('tbody td')[2]!.text()).toBe('已删除')
})
it('审核题号可保存且清空提交null，保留来源其他字段', async () => {
  const fixture = {
    ...reviewFixture(questionId),
    draft: { ...reviewFixture().draft, source: { question_no: 12, page_no: 3 } },
  }
  const { wrapper, request } = await render(
    AdminQuestionView,
    `/admin/questions/${questionId}`,
    (c) =>
      c.method === 'PUT' ? { ...fixture, draft: (c.data as { draft: unknown }).draft } : fixture,
  )
  const editor = () => wrapper.get('[data-test="question-no-editor"] input')
  expect(editor().element).toHaveProperty('value', '12')
  await editor().setValue('13')
  await wrapper.get('[data-test="save-question"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toMatchObject({
    draft: { source: { question_no: 13, page_no: 3 } },
  })
  await editor().setValue('')
  await wrapper.get('[data-test="save-question"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toMatchObject({
    draft: { source: { question_no: null, page_no: 3 } },
  })
})
it.each(['0', '-1', '1.5', '2147483648', 'abc'])(
  '非法题号%s不提交保存也不能确认旧版本',
  async (value) => {
    const { wrapper, request } = await render(
      AdminQuestionView,
      `/admin/questions/${questionId}`,
      () => reviewFixture(questionId),
    )
    await wrapper.get('[data-test="question-no-editor"] input').setValue(value)
    await wrapper.get('[data-test="save-question"]').trigger('click')
    await flushPromises()
    expect(request.mock.calls.some(([config]) => config.method === 'PUT')).toBe(false)
    expect(wrapper.get('[data-test="confirm-question"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('题号须为1至2147483647的整数')
  },
)
it('无题号草稿不强制填写也能保存', async () => {
  const { wrapper, request } = await render(
    AdminQuestionView,
    `/admin/questions/${questionId}`,
    () => reviewFixture(questionId),
  )
  await wrapper.get('[data-test="stem-editor"] textarea').setValue('修正题干')
  await wrapper.get('[data-test="save-question"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toMatchObject({
    draft: { source: { question_no: null } },
  })
})
it('活跃页每3秒刷新题目与PDF两个列表，完成后停止，轮询不清空表格且不重叠', async () => {
  vi.useFakeTimers()
  let finish!: (value: unknown) => void
  let polling = false
  const task = { id: 't1', status: 'queued', createdAt: '2026-11-08T00:00:00Z' }
  const initial = bankResponder(
    [documentFixture({ reviewingCount: 0, queuedCount: 2 })],
    [task],
    [{ id: 'q1', questionNo: null, status: 'draft', draft: { stem_md: '保留行' } }],
  )
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (polling && c.url?.endsWith('/documents'))
      return new Promise((resolve) => {
        finish = resolve
      })

    return initial(c)
  })
  request.mockClear()
  polling = true
  await vi.advanceTimersByTimeAsync(3000)
  expect(request).toHaveBeenCalledTimes(3)
  expect(wrapper.text()).toContain('保留行')
  await openDocuments(wrapper)
  expect(wrapper.findAllComponents({ name: 'Table' })[1]!.props('data')).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(3)
  finish({ list: [documentFixture()], total: 1 })
  await flushPromises()
  await vi.advanceTimersByTimeAsync(6000)
  expect(request).toHaveBeenCalledTimes(3)
})
it('KeepAlive失活和离开路由停止轮询，激活刷新且保留导入输入，卸载清理', async () => {
  vi.useFakeTimers()
  const { wrapper, request, router, active } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder([documentFixture({ reviewingCount: 0, processingCount: 2 })]),
    true,
  )
  await openManual(wrapper)
  await wrapper.get('[data-test="single-text"] textarea').setValue('未提交输入')
  active.value = false
  await flushPromises()
  request.mockClear()
  await vi.advanceTimersByTimeAsync(6000)
  expect(request).not.toHaveBeenCalled()
  active.value = true
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(3)
  expect(wrapper.get('textarea').element).toHaveProperty('value', '未提交输入')
  request.mockClear()
  await vi.advanceTimersByTimeAsync(3000)
  expect(request).toHaveBeenCalledTimes(3)
  await router.push(`/admin/questions/${questionId}?bankId=${bankId}`)
  await flushPromises()
  request.mockClear()
  await vi.advanceTimersByTimeAsync(6000)
  expect(request).not.toHaveBeenCalled()
  await router.push(`/admin/banks/${bankId}`)
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(3)
  request.mockClear()
  wrapper.unmount()
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  await vi.advanceTimersByTimeAsync(6000)
  expect(request).not.toHaveBeenCalled()
})
it('开始生成必须经计费确认且失败不自动重试', async () => {
  vi.useFakeTimers()
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') throw new Error('生成提交失败')
    return bankResponder([documentFixture({ status: 'staged', totalCount: 0, reviewingCount: 0 })])(
      c,
    )
  })
  await openDocuments(wrapper)
  const confirm = wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((node) => node.text().includes('开始生成草稿'))!
  expect(confirm.props('content')).toContain('费用')
  await confirm.get('button').trigger('click')
  await flushPromises()
  expect(request.mock.calls.some(([config]) => config.method === 'POST')).toBe(false)
  confirm.vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: '/api/v1/admin/public-documents/d1/start',
      data: {},
    }),
  )
  await vi.advanceTimersByTimeAsync(12000)
  expect(request.mock.calls.filter(([config]) => config.method === 'POST')).toHaveLength(1)
})
it('切换另一题库不会被旧题库未完成轮询阻塞，也不接收旧响应', async () => {
  vi.useFakeTimers()
  const nextId = '33333333-3333-3333-3333-333333333333'
  let finish!: (value: unknown) => void
  let polling = false
  const respond = bankResponder([documentFixture({ reviewingCount: 0, processingCount: 2 })])
  const { wrapper, request, router } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    (c) => {
      if (polling && c.url?.includes(bankId) && c.url.endsWith('/documents'))
        return new Promise((resolve) => {
          finish = resolve
        })
      if (c.url?.endsWith(nextId)) return { id: nextId, name: '另一试卷', examType: 'career_a' }
      return respond(c)
    },
  )
  polling = true
  await vi.advanceTimersByTimeAsync(3000)
  await router.push(`/admin/banks/${nextId}`)
  await flushPromises()
  request.mockClear()
  await vi.advanceTimersByTimeAsync(3000)
  expect(request).toHaveBeenCalledTimes(3)
  expect(request.mock.calls.every(([config]) => config.url?.includes(nextId))).toBe(true)
  finish({ list: [documentFixture({ sourceName: '旧试卷错误' })], total: 1 })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧试卷错误')
})
it('失活前在途轮询结束不会重启定时器', async () => {
  vi.useFakeTimers()
  let finish!: (value: unknown) => void
  let polling = false
  const respond = bankResponder([documentFixture({ reviewingCount: 0, processingCount: 2 })])
  const { request, active } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    (c) => {
      if (polling && c.url?.endsWith('/documents'))
        return new Promise((resolve) => {
          finish = resolve
        })
      return respond(c)
    },
    true,
  )
  polling = true
  await vi.advanceTimersByTimeAsync(3000)
  active.value = false
  await flushPromises()
  request.mockClear()
  finish({ list: [], total: 0 })
  await flushPromises()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).not.toHaveBeenCalled()
})
it('审核返回详情刷新确认状态且保留已有导入输入', async () => {
  let confirmed = false
  const { wrapper, router } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) =>
    bankResponder(
      [],
      [],
      [
        {
          id: questionId,
          questionNo: null,
          status: confirmed ? 'confirmed' : 'draft',
          draft: { stem_md: '题干' },
        },
      ],
    )(c),
  )
  await openManual(wrapper)
  await wrapper.get('[data-test="single-text"] textarea').setValue('保留输入')
  await router.push(`/admin/questions/${questionId}?bankId=${bankId}`)
  confirmed = true
  await router.push(`/admin/banks/${bankId}`)
  await flushPromises()
  expect(wrapper.findAllComponents({ name: 'Table' })[0]!.text()).toContain('已确认')
  expect(wrapper.get('textarea').element).toHaveProperty('value', '保留输入')
})
it('同组件路由切换会清除旧题并重新读取新ID', async () => {
  const { wrapper, router, request } = await render(
    AdminQuestionView,
    '/admin/questions/11111111-1111-1111-1111-111111111111',
    (c) => reviewFixture(c.url?.split('/').pop()),
  )
  await router.push('/admin/questions/22222222-2222-2222-2222-222222222222')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: '/api/v1/admin/public-questions/22222222-2222-2222-2222-222222222222',
    }),
  )
  expect(wrapper.text()).toContain('原材料-22222222-2222-2222-2222-222222222222')
  expect(wrapper.text()).not.toContain('原材料-11111111-1111-1111-1111-111111111111')
})
it('保存请求未完成时锁定编辑，避免新输入被响应覆盖', async () => {
  let resolve!: (v: unknown) => void
  const pending = new Promise((r) => {
    resolve = r
  })
  const { wrapper } = await render(
    AdminQuestionView,
    '/admin/questions/11111111-1111-1111-1111-111111111111',
    (c) => (c.method === 'PUT' ? pending : reviewFixture()),
  )
  await wrapper.get('[data-test="stem-editor"] textarea').setValue('修改')
  await wrapper.get('[data-test="save-question"]').trigger('click')
  expect(wrapper.get('[data-test="stem-editor"] textarea').attributes('disabled')).toBeDefined()
  resolve(reviewFixture())
  await flushPromises()
})
it('显示原答案模型建议与冲突解析而不混入正式解析', async () => {
  const { wrapper } = await render(
    AdminQuestionView,
    '/admin/questions/11111111-1111-1111-1111-111111111111',
    () => reviewFixture(),
  )
  expect(wrapper.text()).toContain('原答案：B')
  expect(wrapper.text()).toContain('模型建议：A')
  expect(wrapper.text()).toContain('针对A的冲突解析')
})
it('公共库第二页通过分页参数获取而不是截断历史内容', async () => {
  const { wrapper, request } = await render(AdminBanksView, '/admin/banks', () => ({
    list: [],
    total: 101,
    page: 1,
    size: 20,
  }))
  const pagination = wrapper.findComponent({ name: 'Pagination' })
  expect(pagination.exists()).toBe(true)
  pagination.vm.$emit('change', 2)
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ params: { page: 2, size: 20, keyword: '', examType: '' } }),
  )
})
it('题库搜索重置页码，分页保留筛选，清空恢复全量查询', async () => {
  const { wrapper, request } = await render(AdminBanksView, '/admin/banks', () => ({
    list: [],
    total: 60,
    page: 1,
    size: 20,
  }))
  const pagination = () => wrapper.findComponent({ name: 'Pagination' })
  pagination().vm.$emit('change', 2)
  await flushPromises()
  await wrapper.get('#bank-keyword').setValue('  职测  ')
  expect(request.mock.lastCall?.[0].params).toEqual({
    page: 2,
    size: 20,
    keyword: '',
    examType: '',
  })
  await wrapper.get('[data-test="bank-filter"]').trigger('submit')
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toEqual({
    page: 1,
    size: 20,
    keyword: '职测',
    examType: '',
  })
  pagination().vm.$emit('pageSizeChange', 50)
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toEqual({
    page: 1,
    size: 50,
    keyword: '职测',
    examType: '',
  })
  expect(wrapper.get('[data-test="bank-pagination"]').classes()).toContain('user-pagination')
  expect(wrapper.get('[data-test="bank-pagination"]').text()).toContain('共 60 个匹配题库')
  await wrapper.get('[data-test="reset-bank-filters"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toEqual({
    page: 1,
    size: 50,
    keyword: '',
    examType: '',
  })
})

it('公共题库列表通过真实API加载，失败不显示演示数据', async () => {
  const { wrapper, request } = await render(AdminBanksView, '/admin/banks', () => ({
    list: [
      {
        id: 'bank-1',
        name: '公共职测',
        examType: 'career_a',
        subject: 'vocational_aptitude',
        createdAt: '2026-11-08T00:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  }))
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ url: '/api/v1/admin/public-banks', method: 'GET' }),
  )
  expect(wrapper.text()).toContain('公共职测')
  expect(wrapper.text()).toContain('新建题库')
})
it('非导入人不能确认；草稿不能直接发布', async () => {
  const { wrapper } = await render(
    AdminQuestionView,
    '/admin/questions/11111111-1111-1111-1111-111111111111',
    () => ({
      id: 'q1',
      bankId: 'b1',
      taskId: 't1',
      importedBy: '2',
      status: 'draft',
      version: 1,
      sourceText: '原材料',
      draft: {
        type: 'single_choice',
        stem_md: '合成题干',
        options: [
          { key: 'A', text_md: '甲' },
          { key: 'B', text_md: '乙' },
        ],
        answer: { keys: ['A'], source: 'ai_generated' },
        explanation_md: '建议解析',
        knowledge_points: ['GK'],
        difficulty: 3,
      },
      evidence: {
        warnings: ['ai_answer_requires_review'],
        provenance: { web_search_status: 'no_results', web_sources: [] },
      },
    }),
  )
  expect(wrapper.get('[data-test="confirm-question"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="publish-question"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('尚无可用检索依据')
  expect(wrapper.text()).toContain('原材料')
})
it('编辑之后必须保存再确认，不能确认旧版本', async () => {
  const { wrapper, request } = await render(
    AdminQuestionView,
    '/admin/questions/11111111-1111-1111-1111-111111111111',
    () => ({
      id: 'q1',
      bankId: 'b1',
      taskId: 't1',
      importedBy: '1',
      status: 'draft',
      version: 1,
      sourceText: '原材料',
      draft: {
        type: 'single_choice',
        stem_md: '合成题干',
        options: [
          { key: 'A', text_md: '甲' },
          { key: 'B', text_md: '乙' },
        ],
        answer: { keys: ['A'], source: 'ai_generated' },
        explanation_md: '建议解析',
        knowledge_points: ['GK'],
        difficulty: 3,
      },
      evidence: { warnings: [], provenance: { web_sources: [] } },
    }),
  )
  await wrapper.get('[data-test="stem-editor"] textarea').setValue('修正后的题干')
  expect(wrapper.get('[data-test="confirm-question"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-test="save-question"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'PUT',
      data: expect.objectContaining({
        version: 1,
        draft: expect.objectContaining({ stem_md: '修正后的题干' }),
      }),
    }),
  )
})

it('单题任务查询、下架与删除API使用明确方法、版本和DELETE请求体', async () => {
  const request = vi.fn().mockResolvedValue(null)
  const api = createPublicBankApi({ request } as unknown as Parameters<
    typeof createPublicBankApi
  >[0])
  await api.importTask('t/1')
  await api.withdraw('q/1', 7)
  await api.deleteQuestion('q/1', 8)
  expect(request.mock.calls.map(([c]) => c)).toEqual([
    { method: 'GET', url: '/api/v1/admin/public-imports/t%2F1' },
    { method: 'POST', url: '/api/v1/admin/public-questions/q%2F1/withdraw', data: { version: 7 } },
    { method: 'DELETE', url: '/api/v1/admin/public-questions/q%2F1', data: { version: 8 } },
  ])
})
it('列表已发布只可查看下架，未发布可编辑删除且操作带版本', async () => {
  let withdrawn = false
  let deleted = false
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') {
      withdrawn = true
      return { ...reviewFixture('q1'), version: 4 }
    }
    if (c.method === 'DELETE') {
      deleted = true
      return null
    }
    return bankResponder(
      [],
      [],
      [
        {
          id: 'q1',
          status: withdrawn ? 'draft' : 'published',
          version: withdrawn ? 4 : 3,
          draft: { stem_md: '发布题' },
        },
        ...(deleted
          ? []
          : [{ id: 'q2', status: 'confirmed', version: 2, draft: { stem_md: '确认题' } }]),
      ],
    )(c)
  })
  const rows = () => wrapper.findAllComponents({ name: 'Table' })[0]!.findAll('tbody tr')
  expect(rows()[0]!.text()).toContain('查看')
  expect(rows()[0]!.text()).toContain('下架')
  expect(rows()[0]!.text()).not.toContain('编辑')
  expect(rows()[0]!.text()).not.toContain('删除')
  expect(rows()[1]!.text()).toContain('编辑')
  expect(rows()[1]!.text()).toContain('删除')
  expect(request.mock.calls.some(([c]) => c.method !== 'GET')).toBe(false)
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.text().includes('下架'))!
    .vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: '/api/v1/admin/public-questions/q1/withdraw',
      data: { version: 3 },
    }),
  )
  expect(rows()[0]!.text()).toContain('未确认')
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.find('[data-test="delete-q2"]').exists())!
    .vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'DELETE',
      url: '/api/v1/admin/public-questions/q2',
      data: { version: 2 },
    }),
  )
  expect(rows()).toHaveLength(1)
})
it('审核页下架清确认后须重新确认发布，删除回所属题库', async () => {
  const fixture = {
    ...reviewFixture(questionId),
    bankId,
    status: 'published',
    version: 4,
    confirmedBy: '1',
    confirmedAt: '2026-11-08T00:00:00Z',
  }
  const { wrapper, request, router } = await render(
    AdminQuestionView,
    `/admin/questions/${questionId}`,
    (c) =>
      c.method === 'DELETE'
        ? null
        : c.method === 'POST'
          ? { ...fixture, status: 'draft', version: 5, confirmedBy: null, confirmedAt: null }
          : fixture,
  )
  expect(wrapper.find('[data-test="delete-question"]').exists()).toBe(false)
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.find('[data-test="withdraw-question"]').exists())!
    .vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      data: { version: 4 },
      url: `/api/v1/admin/public-questions/${questionId}/withdraw`,
    }),
  )
  expect(wrapper.text()).toContain('版本 5 · 未确认')
  expect(wrapper.get('[data-test="publish-question"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="confirm-question"]').attributes('disabled')).toBeUndefined()
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((c) => c.find('[data-test="delete-question"]').exists())!
    .vm.$emit('ok')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ method: 'DELETE', data: { version: 5 } }),
  )
  expect(router.currentRoute.value.path).toBe(`/admin/banks/${bankId}`)
})

it('分体按钮箭头只有单题录入，正文直接打开PDF且没有提交操作', async () => {
  vi.useFakeTimers()
  const { wrapper, request } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    bankResponder(),
    false,
    true,
  )
  const dropdown = wrapper.findComponent({ name: 'DropdownButton' })
  const buttons = dropdown.findAll('button')
  expect(buttons[0]!.text()).toBe('从 PDF 导入')
  expect(buttons[1]!.find('.arco-icon-down').exists()).toBe(true)
  await buttons[1]!.trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(300)
  await flushPromises()
  const options = wrapper.findAllComponents({ name: 'Doption' })
  expect(options).toHaveLength(1)
  expect(options[0]!.text()).toBe('单题录入')
  await buttons[0]!.trigger('click')
  await flushPromises()
  expect(document.body.querySelector('.arco-modal')?.textContent).toContain('上传并离线解析')
  expect(request.mock.calls.some(([c]) => c.method !== 'GET')).toBe(false)
})
it.each(['withdraw', 'deleteQuestion'] as const)(
  '审核页%s失败仅通知一次，busy防重复且不离开',
  async (kind) => {
    let reject!: (e: Error) => void
    const pending = new Promise((_, r) => {
      reject = r
    })
    const feedback = vi.fn()
    const cleanup = installErrorFeedback(feedback)
    try {
      const { wrapper, request, router } = await render(
        AdminQuestionView,
        `/admin/questions/${questionId}`,
        (c) =>
          c.method === 'GET'
            ? { ...reviewFixture(questionId), status: kind === 'withdraw' ? 'published' : 'draft' }
            : pending,
      )
      const test = kind === 'withdraw' ? 'withdraw-question' : 'delete-question'
      const confirm = wrapper
        .findAllComponents({ name: 'Popconfirm' })
        .find((c) => c.find(`[data-test="${test}"]`).exists())!
      confirm.vm.$emit('ok')
      await flushPromises()
      expect(wrapper.get(`[data-test="${test}"]`).attributes('disabled')).toBeDefined()
      confirm.vm.$emit('ok')
      await flushPromises()
      expect(request.mock.calls.filter(([c]) => c.method !== 'GET')).toHaveLength(1)
      reject(new Error('失败'))
      await flushPromises()
      expect(feedback).toHaveBeenCalledTimes(1)
      expect(router.currentRoute.value.path).toBe(`/admin/questions/${questionId}`)
      expect(wrapper.get(`[data-test="${test}"]`).attributes('disabled')).toBeUndefined()
    } finally {
      cleanup()
    }
  },
)

function taskFixture(status = 'queued') {
  return {
    id: 't1',
    bankId,
    status,
    questionId: status === 'reviewing' ? 'q1' : null,
    questionStatus: status === 'reviewing' ? 'draft' : null,
    errorMessage: null,
    createdAt: '2026-11-08T00:00:00Z',
  }
}
it('单题只显式POST一次，查询任务完成后刷新统一列表为未确认', async () => {
  vi.useFakeTimers()
  let completed = false
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') return taskFixture()
    if (c.url?.endsWith('/public-imports/t1')) {
      completed = true
      return taskFixture('reviewing')
    }
    return bankResponder(
      [],
      [],
      completed ? [{ id: 'q1', status: 'draft', version: 1, draft: { stem_md: '解析题目' } }] : [],
    )(c)
  })
  const dialog = await openManual(wrapper)
  await dialog.get('textarea').setValue('题干 A.甲 B.乙')
  await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  expect(dialog.text()).toContain('解析中')
  expect(dialog.get('textarea').attributes('disabled')).toBeDefined()
  await vi.advanceTimersByTimeAsync(3000)
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ method: 'GET', url: '/api/v1/admin/public-imports/t1' }),
  )
  expect(wrapper.findAllComponents({ name: 'Table' })[0]!.text()).toContain('解析题目未确认')
  await vi.advanceTimersByTimeAsync(12000)
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
  expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(1)
})
it('单题任务失活停止轮询，激活只查询现有任务不重发POST，卸载清理', async () => {
  vi.useFakeTimers()
  const { wrapper, request, active } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    (c) =>
      c.method === 'POST' || c.url?.endsWith('/public-imports/t1')
        ? taskFixture()
        : bankResponder()(c),
    true,
  )
  const dialog = await openManual(wrapper)
  await dialog.get('textarea').setValue('保留输入')
  await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  active.value = false
  await flushPromises()
  request.mockClear()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).not.toHaveBeenCalled()
  active.value = true
  await flushPromises()
  await vi.advanceTimersByTimeAsync(3000)
  expect(request.mock.calls.some(([c]) => c.url?.endsWith('/public-imports/t1'))).toBe(true)
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  expect(wrapper.get('[data-test="single-text"] textarea').element).toHaveProperty(
    'value',
    '保留输入',
  )
  wrapper.unmount()
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  request.mockClear()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).not.toHaveBeenCalled()
})
it('解析失败使用固定错误通知且保留中性任务状态，不回显服务端错误或自动重试', async () => {
  vi.useFakeTimers()
  const feedback = vi.fn()
  const cleanup = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) =>
      c.method === 'POST'
        ? taskFixture()
        : c.url?.endsWith('/public-imports/t1')
          ? { ...taskFixture('failed'), errorMessage: 'secret traceback' }
          : bankResponder()(c),
    )
    const dialog = await openManual(wrapper)
    await dialog.get('textarea').setValue('题干')
    await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(3000)
    expect(dialog.text()).toContain('解析未完成')
    expect(dialog.text()).not.toContain('secret traceback')
    expect(dialog.text()).toContain('t1')
    expect(feedback).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(12000)
    expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
  } finally {
    cleanup()
  }
})

it('任务查询失败暂停自动轮询，手动查询只GET，不重复模型POST', async () => {
  vi.useFakeTimers()
  let fail = true
  const feedback = vi.fn()
  const cleanup = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render(
      AdminBankDetailView,
      `/admin/banks/${bankId}`,
      (c) => {
        if (c.method === 'POST') return taskFixture()
        if (c.url?.endsWith('/public-imports/t1')) {
          if (fail) throw new Error('offline')
          return taskFixture('reviewing')
        }
        return bankResponder()(c)
      },
    )
    const dialog = await openManual(wrapper)
    await dialog.get('textarea').setValue('题干')
    await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(3000)
    expect(feedback).toHaveBeenCalledTimes(1)
    expect(dialog.text()).toContain('查询解析进度')
    const count = request.mock.calls.length
    await vi.advanceTimersByTimeAsync(15000)
    expect(request.mock.calls).toHaveLength(count)
    fail = false
    await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
    await flushPromises()
    expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
    expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(
      2,
    )
  } finally {
    cleanup()
  }
})
it('单题取消重开保留原始文本，切换题库不会接收旧任务响应', async () => {
  let finish!: (value: unknown) => void
  const pending = new Promise((resolve) => {
    finish = resolve
  })
  const nextId = '33333333-3333-3333-3333-333333333333'
  const { wrapper, router } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) => {
    if (c.method === 'POST') return pending
    if (c.url?.endsWith(nextId)) return { id: nextId, name: '另一题库', examType: 'career_a' }
    return bankResponder()(c)
  })
  const dialog = await openManual(wrapper)
  await dialog.get('textarea').setValue('未提交输入')
  await dialog.get('.arco-modal-footer .arco-btn-secondary').trigger('click')
  await flushPromises()
  const reopened = await openManual(wrapper)
  expect(reopened.get('textarea').element).toHaveProperty('value', '未提交输入')
  await reopened.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  await router.push(`/admin/banks/${nextId}`)
  await flushPromises()
  finish(taskFixture('reviewing'))
  await flushPromises()
  expect(wrapper.text()).not.toContain('解析完成')
  const nextDialog = await openManual(wrapper)
  expect(nextDialog.get('textarea').element).toHaveProperty('value', '')
})

it('单题轮询有界40次后暂停且仅手动GET恢复，不重发付费POST', async () => {
  vi.useFakeTimers()
  const { wrapper, request } = await render(AdminBankDetailView, `/admin/banks/${bankId}`, (c) =>
    c.method === 'POST' || c.url?.endsWith('/public-imports/t1')
      ? taskFixture()
      : bankResponder()(c),
  )
  const dialog = await openManual(wrapper)
  await dialog.get('textarea').setValue('题干')
  await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(120000)
  expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(40)
  expect(dialog.text()).toContain('查询解析进度')
  await vi.advanceTimersByTimeAsync(9000)
  expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(40)
  await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(41)
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
})
it('单题查询在途不重叠，失活后响应到达不重启轮询', async () => {
  vi.useFakeTimers()
  let finish!: (value: unknown) => void
  const { wrapper, request, active } = await render(
    AdminBankDetailView,
    `/admin/banks/${bankId}`,
    (c) => {
      if (c.method === 'POST') return taskFixture()
      if (c.url?.endsWith('/public-imports/t1'))
        return new Promise((resolve) => {
          finish = resolve
        })
      return bankResponder()(c)
    },
    true,
  )
  const dialog = await openManual(wrapper)
  await dialog.get('textarea').setValue('题干')
  await dialog.get('.arco-modal-footer .arco-btn-primary').trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(12000)
  expect(request.mock.calls.filter(([c]) => c.url?.endsWith('/public-imports/t1'))).toHaveLength(1)
  active.value = false
  await flushPromises()
  finish(taskFixture())
  await flushPromises()
  request.mockClear()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).not.toHaveBeenCalled()
})
