import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, KeepAlive, nextTick, ref } from 'vue'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { installErrorFeedback } from '@quest/shared'
import { authRedirect, safeNext, useAuthStore } from '@quest/shared'
import { createAppRouter } from '../router'
import AdminSyllabiView from '../views/AdminSyllabiView.vue'
import AdminSyllabusDetailView from '../views/AdminSyllabusDetailView.vue'
import AdminLayout from '../layouts/AdminLayout.vue'
import ProfileView from '../views/ProfileView.vue'

const id = '11111111-1111-1111-1111-111111111111'
const section = {
  ordinal: 1,
  title: '能力要求',
  path: '职测/能力要求',
  kind: 'requirement',
  scope: 'vocational_aptitude',
  enabled: false,
  pageStart: 1,
  pageEnd: 2,
  printedPages: 'I–II',
  text: '原章节内容',
}
const fixture = () => ({
  id,
  title: '合成考纲',
  examType: 'career_a',
  examYear: 2027,
  versionLabel: '第一版',
  status: 'draft',
  revision: 3,
  pageCount: 2,
  sectionCount: 2,
  enabledCount: 0,
  createdAt: '2026-11-09T00:00:00Z',
  issuer: '合成机构',
  publishedDate: null,
  sourceUrl: '',
  sourceName: '考纲.pdf',
  sha256: 'abc',
  pages: [{ page: 1, printedPage: 'I', text: '<script>页原文</script>' }],
  sections: [{ ...section }, { ...section, ordinal: 2, title: '其他科目', scope: 'excluded' }],
  warnings: ['请人工核对范围'],
})
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
async function render(
  component: typeof AdminSyllabiView,
  path: string,
  responder: (c: { method?: string; url?: string; data?: unknown }) => unknown,
  student = false,
) {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('禁止真实网络')))
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.configure('/gateway')
  auth.token = 'token'
  auth.expiresAt = new Date(Date.now() + 3600000).toISOString()
  auth.user = {
    id: '1',
    username: 'test',
    displayName: '测试',
    role: student ? 'student' : 'admin',
    status: 'active',
    mustChangePassword: false,
    createdAt: '2026-11-09T00:00:00Z',
    lastLoginAt: null,
  }
  vi.spyOn(auth, 'restoreSession').mockResolvedValue()
  const request = vi.spyOn(auth, 'request').mockImplementation(async (c) => responder(c) as never)
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: true, DepthRouterView: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request, auth }
}
const page = () => ({ list: [fixture()], total: 60, page: 1, size: 20 })
const jobFixture = (overrides: Record<string, unknown> = {}) => ({
  ...fixture(),
  reviewStatus: 'ready',
  indexStatus: 'not_started',
  reviewError: null,
  indexError: null,
  indexedCount: 0,
  chunkCount: 0,
  embeddingModel: null as string | null,
  embeddingDimensions: null as number | null,
  ...overrides,
})
function confirmPublish(wrapper: VueWrapper) {
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((n) => n.find('[data-test="publish-syllabus"]').exists())!
    .vm.$emit('ok')
}
it('AI排队/处理中禁止编辑发布，每3秒只查详情，完成后展示问题且停止轮询', async () => {
  vi.useFakeTimers()
  let value = jobFixture({ reviewStatus: 'queued' })
  const { wrapper, request } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () =>
    structuredClone(value),
  )
  expect(wrapper.text()).toContain('等待 AI 整理')
  expect(wrapper.get('[data-test="detail-title"] input').attributes('disabled')).toBeDefined()
  expect(wrapper.find('[data-test="edit-section-1"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="publish-syllabus"]').exists()).toBe(false)
  value = jobFixture({ reviewStatus: 'processing' })
  await vi.advanceTimersByTimeAsync(2999)
  expect(request).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  await flushPromises()
  expect(wrapper.text()).toContain('AI 整理中')
  value = jobFixture({ revision: 4, warnings: ['AI建议：核对章节范围'] })
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('AI 整理完成')
  expect(wrapper.text()).toContain('AI建议：核对章节范围')
  expect(wrapper.get('[data-test="detail-title"] input').attributes('disabled')).toBeUndefined()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(3)
  expect(
    request.mock.calls.every(
      ([c]) => c.method === 'GET' && c.url === `/api/v1/admin/syllabi/${id}`,
    ),
  ).toBe(true)
})
it('整理失败仅显式重试携带revision，失败请求不自动重试，重试后恢复轮询', async () => {
  vi.useFakeTimers()
  let fail = true
  const feedback = vi.fn(),
    cleanup = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      (c) => {
        if (c.url?.endsWith('/retry-review')) {
          if (fail) throw new Error('安全失败')
          return jobFixture({ reviewStatus: 'queued', revision: 4 })
        }
        return jobFixture({
          reviewStatus: 'failed',
          reviewError: 'AI 整理失败，请重试',
          warnings: ['需人工核对'],
        })
      },
    )
    expect(wrapper.text()).toContain('AI 整理失败，请重试')
    expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(9000)
    expect(request).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-test="retry-review"]').trigger('click')
    await flushPromises()
    expect(request.mock.lastCall?.[0]).toMatchObject({
      method: 'POST',
      url: `/api/v1/admin/syllabi/${id}/retry-review`,
      data: { revision: 3 },
    })
    expect(feedback).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(9000)
    expect(request).toHaveBeenCalledTimes(2)
    fail = false
    await wrapper.get('[data-test="retry-review"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('等待 AI 整理')
    await vi.advanceTimersByTimeAsync(3000)
    expect(request).toHaveBeenCalledTimes(4)
  } finally {
    cleanup()
  }
})
it('发布与索引独立，发布后显示真实进度和模型维度，索引完成停止轮询', async () => {
  vi.useFakeTimers()
  let value = jobFixture({ sections: [{ ...section, enabled: true }] })
  const { wrapper, request } = await render(
    AdminSyllabusDetailView,
    `/admin/syllabi/${id}`,
    (c) => {
      if (c.url?.endsWith('/publish'))
        value = jobFixture({
          status: 'published',
          revision: 4,
          indexStatus: 'queued',
          chunkCount: 5,
          sections: [{ ...section, enabled: true }],
        })
      return structuredClone(value)
    },
  )
  expect(wrapper.text()).toContain('索引未开始')
  expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeUndefined()
  confirmPublish(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('已发布')
  expect(wrapper.text()).toContain('等待索引')
  expect(wrapper.text()).not.toContain('索引就绪')
  expect(request.mock.lastCall?.[0]).toMatchObject({ method: 'POST', data: { revision: 3 } })
  value = {
    ...value,
    indexStatus: 'processing',
    indexedCount: 2,
    embeddingModel: '合成模型',
    embeddingDimensions: 1536,
  }
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.get('[data-test="index-progress"]').text()).toContain('2 / 5')
  expect(wrapper.text()).toContain('合成模型')
  expect(wrapper.text()).toContain('1536')
  value = { ...value, indexStatus: 'ready', indexedCount: 5 }
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('索引就绪')
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(4)
})
it.each(['published', 'archived'])(
  '%s索引失败可显式重建且携带revision，不改发布状态',
  async (status) => {
    vi.useFakeTimers()
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      (c) =>
        jobFixture({
          status,
          indexStatus: c.method === 'POST' ? 'queued' : 'failed',
          indexError: '索引失败，请重建',
          revision: c.method === 'POST' ? 4 : 3,
        }),
    )
    expect(wrapper.text()).toContain('索引失败，请重建')
    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()
    expect(request.mock.lastCall?.[0]).toMatchObject({
      method: 'POST',
      url: `/api/v1/admin/syllabi/${id}/rebuild-index`,
      data: { revision: 3 },
    })
    expect(wrapper.text()).toContain(status === 'published' ? '已发布' : '已归档')
    expect(wrapper.text()).toContain('等待索引')
  },
)
it('列表操作栏可直接重试失败的AI整理，并显示具体失败原因', async () => {
  const { wrapper, request } = await render(AdminSyllabiView, '/admin/syllabi', (c) =>
    c.method === 'POST'
      ? { ...fixture(), reviewStatus: 'queued', reviewError: null }
      : {
          list: [
            {
              ...fixture(),
              reviewStatus: 'failed',
              reviewError: '模型响应超时（后台批处理允许较长耗时，可重试或减少单次章节数）',
              revision: 3,
            },
          ],
          total: 1,
          page: 1,
          size: 20,
        },
  )
  expect(wrapper.text()).toContain('模型响应超时')
  await wrapper.get('[data-test="list-retry-review"]').trigger('click')
  await flushPromises()
  // 重试后会刷新列表，因此按条件查找而不是只看最后一次调用。
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: `/api/v1/admin/syllabi/${id}/retry-review`,
      data: { revision: 3 },
    }),
  )
})
it('草稿不提供重建；缺省任务字段兼容显示未开始，不伪造AI或索引就绪', async () => {
  const { wrapper } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () => fixture())
  expect(wrapper.text()).toContain('AI 整理未开始')
  expect(wrapper.text()).toContain('索引未开始')
  expect(wrapper.text()).not.toContain('索引就绪')
  expect(wrapper.find('[data-test="rebuild-index"]').exists()).toBe(false)
})
it('真实未开始不允许跳过AI审核发布，缺省旧fixture仍可人工审核', async () => {
  const { wrapper, request } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () =>
    jobFixture({ reviewStatus: 'not_started', sections: [{ ...section, enabled: true }] }),
  )
  expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeDefined()
  confirmPublish(wrapper)
  await flushPromises()
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
})
it('离开活动详情立即停止后续GET，迟到响应不污染离开后的状态', async () => {
  vi.useFakeTimers()
  let resolve!: (value: unknown) => void
  let calls = 0
  const { wrapper, router, request } = await render(
    AdminSyllabusDetailView,
    `/admin/syllabi/${id}`,
    () => {
      if (++calls === 2)
        return new Promise((r) => {
          resolve = r
        })
      return jobFixture({ reviewStatus: 'processing' })
    },
  )
  await vi.advanceTimersByTimeAsync(3000)
  await router.push('/admin/syllabi')
  resolve(jobFixture({ title: '离开后的旧响应', revision: 99 }))
  await flushPromises()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).not.toContain('离开后的旧响应')
})
it('索引重建失败保留已发布与错误状态，只能手动重试且防重复提交', async () => {
  vi.useFakeTimers()
  let reject!: (error: Error) => void
  const feedback = vi.fn(),
    cleanup = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      (c) => {
        if (c.method === 'POST')
          return new Promise((_, r) => {
            reject = r
          })
        return jobFixture({
          status: 'published',
          indexStatus: 'failed',
          indexError: '索引失败，请重建',
        })
      },
    )
    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
    reject(new Error('安全失败'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(9000)
    expect(wrapper.text()).toContain('已发布')
    expect(wrapper.text()).toContain('索引失败，请重建')
    expect(request).toHaveBeenCalledTimes(2)
    expect(feedback).toHaveBeenCalledTimes(1)
  } finally {
    cleanup()
  }
})
it('轮询保留未保存编辑，拒绝旧revision；更新保存仍带编辑时revision防覆盖', async () => {
  vi.useFakeTimers()
  let value = jobFixture({ indexStatus: 'processing', revision: 5 })
  const { wrapper, request } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () =>
    structuredClone(value),
  )
  await wrapper.get('[data-test="detail-title"] input').setValue('我的未保存标题')
  value = jobFixture({ revision: 4, title: '旧请求污染', indexStatus: 'ready' })
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('修订 5')
  expect(wrapper.get('[data-test="detail-title"] input').element).toHaveProperty(
    'value',
    '我的未保存标题',
  )
  value = jobFixture({ revision: 6, title: '新的服务器标题', indexStatus: 'ready' })
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('修订 6')
  expect(wrapper.get('[data-test="detail-title"] input').element).toHaveProperty(
    'value',
    '我的未保存标题',
  )
  await wrapper.get('[data-test="save-syllabus"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0]).toMatchObject({
    method: 'PUT',
    data: { title: '我的未保存标题', revision: 5 },
  })
})
it('离开详情停止轮询并丢弃在途结果，切换id不让旧详情污染新详情', async () => {
  vi.useFakeTimers()
  let resolve!: (value: unknown) => void
  let calls = 0
  const otherId = '22222222-2222-2222-2222-222222222222'
  const { wrapper, request, router } = await render(
    AdminSyllabusDetailView,
    `/admin/syllabi/${id}`,
    (c) => {
      if (c.url?.endsWith(otherId)) return jobFixture({ id: otherId, title: '另一版本' })
      if (++calls === 2)
        return new Promise((r) => {
          resolve = r
        })
      return jobFixture({ reviewStatus: 'processing' })
    },
  )
  await vi.advanceTimersByTimeAsync(3000)
  await router.push(`/admin/syllabi/${otherId}`)
  await flushPromises()
  resolve(jobFixture({ title: '迟到的旧版本', revision: 99 }))
  await flushPromises()
  expect(wrapper.text()).toContain('另一版本')
  expect(wrapper.text()).not.toContain('迟到的旧版本')
  await router.push('/admin/syllabi')
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(3)
})
it('失活与卸载清理详情轮询，恢复后仅活动任务继续', async () => {
  vi.useFakeTimers()
  const shown = ref(true)
  const Host = defineComponent({
    setup: () => () =>
      h(KeepAlive, null, { default: () => (shown.value ? h(AdminSyllabusDetailView) : null) }),
  })
  const { wrapper, request } = await render(Host, `/admin/syllabi/${id}`, () =>
    jobFixture({ reviewStatus: 'processing' }),
  )
  expect(request).toHaveBeenCalledTimes(1)
  shown.value = false
  await nextTick()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(1)
  shown.value = true
  await nextTick()
  await vi.advanceTimersByTimeAsync(3000)
  expect(request).toHaveBeenCalledTimes(2)
  wrapper.unmount()
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(2)
})
it('详情轮询GET失败停止，不重试模型或继续刷请求', async () => {
  vi.useFakeTimers()
  const cleanup = installErrorFeedback(vi.fn())
  try {
    let calls = 0
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      () => {
        if (++calls > 1) throw new Error('暂不可用')
        return jobFixture({ reviewStatus: 'processing' })
      },
    )
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.text()).toContain('状态刷新失败')
    await vi.advanceTimersByTimeAsync(9000)
    expect(request).toHaveBeenCalledTimes(2)
  } finally {
    cleanup()
  }
})
it('列表显示整理与索引独立状态，仅手动刷新不轮询', async () => {
  vi.useFakeTimers()
  const { wrapper, request } = await render(AdminSyllabiView, '/admin/syllabi', () => ({
    ...page(),
    list: [
      jobFixture({
        status: 'published',
        reviewStatus: 'ready',
        indexStatus: 'processing',
        indexedCount: 2,
        chunkCount: 5,
        embeddingModel: '合成模型',
        embeddingDimensions: 1536,
      }),
    ],
  }))
  expect(wrapper.text()).toContain('已发布')
  expect(wrapper.text()).toContain('AI 整理完成')
  expect(wrapper.text()).toContain('索引处理中')
  expect(wrapper.text()).toContain('2 / 5')
  expect(wrapper.text()).toContain('合成模型')
  await vi.advanceTimersByTimeAsync(12000)
  expect(request).toHaveBeenCalledTimes(1)
})
it('详情轮询不使并行的原件下载失效', async () => {
  vi.useFakeTimers()
  let resolve!: (blob: Blob) => void
  const create = vi.fn().mockReturnValue('blob:synthetic')
  vi.stubGlobal(
    'URL',
    Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: vi.fn() }),
  )
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const { wrapper } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, (c) => {
    if (c.url?.endsWith('/file'))
      return new Promise((r) => {
        resolve = r
      })
    return jobFixture({ reviewStatus: 'processing' })
  })
  await wrapper.get('[data-test="download-syllabus"]').trigger('click')
  await vi.advanceTimersByTimeAsync(3000)
  const pdf = new Blob(['%PDF'])
  resolve(pdf)
  await flushPromises()
  expect(create).toHaveBeenCalledWith(pdf)
})
it('原件下载经鉴权blob并释放对象URL，不直接拼接公开地址', async () => {
  vi.useFakeTimers()
  const pdf = new Blob(['%PDF'], { type: 'application/pdf' })
  const create = vi.fn().mockReturnValue('blob:synthetic'),
    revoke = vi.fn()
  vi.stubGlobal(
    'URL',
    Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: revoke }),
  )
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  try {
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      (c) => (c.url?.endsWith('/file') ? pdf : fixture()),
    )
    await wrapper.get('[data-test="download-syllabus"]').trigger('click')
    await flushPromises()
    expect(request.mock.lastCall?.[0]).toMatchObject({
      method: 'GET',
      url: `/api/v1/admin/syllabi/${id}/file`,
      responseType: 'blob',
    })
    expect(create).toHaveBeenCalledWith(pdf)
    expect(click).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1000)
    expect(revoke).toHaveBeenCalledWith('blob:synthetic')
  } finally {
    vi.useRealTimers()
  }
})
it('保存冲突保留草稿且防重复，不发布旧revision或自动重试', async () => {
  let reject!: (e: Error) => void
  const pending = new Promise((_, r) => {
    reject = r
  })
  const feedback = vi.fn(),
    cleanup = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render(
      AdminSyllabusDetailView,
      `/admin/syllabi/${id}`,
      (c) => (c.method === 'PUT' ? pending : fixture()),
    )
    await wrapper.get('[data-test="detail-title"] input').setValue('保留修改')
    await wrapper.get('[data-test="save-syllabus"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="detail-title"] input').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test="save-syllabus"]').trigger('click')
    await flushPromises()
    expect(request.mock.calls.filter(([c]) => c.method === 'PUT')).toHaveLength(1)
    reject(new Error('revision冲突'))
    await flushPromises()
    expect(feedback).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="detail-title"] input').element).toHaveProperty(
      'value',
      '保留修改',
    )
    expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeDefined()
    expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  } finally {
    cleanup()
  }
})
it('有非法启用章节时禁止发布，章节页码超出原件不得应用', async () => {
  const { wrapper } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () => ({
    ...fixture(),
    sections: [
      { ...section, enabled: true },
      { ...section, ordinal: 2, scope: 'excluded', enabled: true },
    ],
  }))
  expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-test="edit-section-1"]').trigger('click')
  await flushPromises()
  const dialog = wrapper
    .findAllComponents({ name: 'Modal' })
    .find((n) => n.props('title') === '编辑章节')!
  dialog.findAllComponents({ name: 'InputNumber' })[2]!.vm.$emit('update:modelValue', 3)
  await dialog.get('[data-test="apply-section"]').trigger('click')
  await flushPromises()
  expect(dialog.text()).toContain('物理页码须在原件范围内')
})
it.each(['example', 'other'])('%s章节只保留原文不能启用', async (kind) => {
  const { wrapper } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () => ({
    ...fixture(),
    sections: [{ ...section, kind }],
  }))
  await wrapper.get('[data-test="edit-section-1"]').trigger('click')
  await flushPromises()
  const dialog = wrapper
    .findAllComponents({ name: 'Modal' })
    .find((n) => n.props('title') === '编辑章节')!
  expect(dialog.findComponent({ name: 'Switch' }).props('disabled')).toBe(true)
})
it('考纲菜单与题库并列，精确白名单、层级、面包屑及展开状态同步', async () => {
  const { wrapper, router, auth } = await render(AdminLayout, '/admin/syllabi', () => page())
  expect(wrapper.get('.admin-navbar .arco-menu-selected').text()).toBe('内容管理')
  expect(wrapper.get('.admin-sidebar').text()).toContain('题库管理')
  expect(wrapper.get('.admin-sidebar').text()).toContain('考纲管理')
  const syllabusGroup = wrapper
    .findAll('.admin-sidebar .arco-menu-inline')
    .find((n) => n.text().includes('考纲管理'))!
  expect(syllabusGroup.get('.arco-menu-inline-content').isVisible()).toBe(true)
  expect(syllabusGroup.get('.arco-menu-icon-suffix').classes()).toContain('is-open')
  expect(router.currentRoute.value.meta.pageDeepth).toBe(1)
  await router.push(`/admin/syllabi/${id}`)
  await flushPromises()
  expect(router.currentRoute.value.meta.pageDeepth).toBe(2)
  expect(wrapper.get('.admin-breadcrumb').text()).toContain('考纲详情')
  expect(authRedirect(auth.user, `/admin/syllabi/${id}`)).toBeNull()
  for (const path of [
    '/admin/syllabi/invalid',
    `/admin/syllabi/${id}/edit`,
    '/admin/syllabi-extra',
  ])
    expect(safeNext(auth.user!, path)).toBe('/admin/users')
  expect(authRedirect({ ...auth.user!, role: 'student' }, '/admin/syllabi')).toBe('/practice')
})
it('列表筛选与分页透传，清空重置页码，显示北京时间并可进入详情', async () => {
  const { wrapper, request, router } = await render(AdminSyllabiView, '/admin/syllabi', () =>
    page(),
  )
  expect(wrapper.get('.arco-card').classes()).toContain('admin-users-card')
  expect(wrapper.text()).toContain('2026/11/09 08:00')
  await wrapper.get('#syllabus-keyword').setValue('  考纲  ')
  wrapper.findAllComponents({ name: 'Select' })[0]!.vm.$emit('update:modelValue', 'career_a')
  wrapper.findComponent({ name: 'InputNumber' }).vm.$emit('update:modelValue', 2027)
  await wrapper.get('[data-test="syllabus-filter"]').trigger('submit')
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toEqual({
    page: 1,
    size: 20,
    keyword: '考纲',
    examType: 'career_a',
    examYear: 2027,
    status: '',
  })
  wrapper.findComponent({ name: 'Pagination' }).vm.$emit('change', 2)
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toMatchObject({ page: 2, examYear: 2027 })
  await wrapper.get('[data-test="reset-syllabi"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].params).toMatchObject({
    page: 1,
    keyword: '',
    examYear: undefined,
  })
  await wrapper.get('[data-test="syllabus-detail"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe(`/admin/syllabi/${id}`)
})
it('上传使用Arco标准Modal，仅显式填写年份并确认后提交；失败保留输入', async () => {
  const { wrapper, request } = await render(AdminSyllabiView, '/admin/syllabi', (c) => {
    if (c.method === 'POST') throw new Error('失败')
    return page()
  })
  await wrapper.get('[data-test="upload-syllabus"]').trigger('click')
  await flushPromises()
  const modal = wrapper.get('.arco-modal')
  expect(modal.get('.arco-modal-footer').text()).toContain('取消')
  expect(modal.findComponent({ name: 'InputNumber' }).props('modelValue')).toBeUndefined()
  const upload = modal.findComponent({ name: 'Upload' })
  expect(upload.props('autoUpload')).toBe(false)
  const file = new File(['%PDF'], '2028.pdf', { type: 'application/pdf' })
  const input = upload.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  await modal.get('[data-test="confirm-upload"]').trigger('click')
  await flushPromises()
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  for (const [field, value] of Object.entries({
    title: '人工标题',
    versionLabel: '版本一',
    issuer: '机构',
  }))
    await modal.get(`[data-test="upload-${field}"] input`).setValue(value)
  modal.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'career_a')
  modal.findComponent({ name: 'InputNumber' }).vm.$emit('update:modelValue', 2027)
  await flushPromises()
  await modal.get('[data-test="confirm-upload"]').trigger('click')
  await flushPromises()
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
  expect((request.mock.lastCall?.[0].data as FormData).get('examYear')).toBe('2027')
  expect(modal.isVisible()).toBe(true)
  expect(upload.text()).toContain('2028.pdf')
})
it('章节逐项人工启用，保存完整sections后才能发布；查看页原文安全转义', async () => {
  let value = fixture()
  const { wrapper, request } = await render(
    AdminSyllabusDetailView,
    `/admin/syllabi/${id}`,
    (c) => {
      if (c.method === 'PUT') value = { ...value, ...(c.data as object), revision: 4 }
      if (c.url?.endsWith('/publish')) value = { ...value, status: 'published', revision: 5 }
      return structuredClone(value)
    },
  )
  expect(wrapper.text()).toContain('未启用')
  expect(wrapper.text()).toContain('请人工核对范围')
  await wrapper.get('[data-test="view-pages"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-test="page-text"]').text()).toContain('<script>页原文</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  await wrapper.get('[data-test="close-pages"]').trigger('click')
  await wrapper.get('[data-test="edit-section-1"]').trigger('click')
  await flushPromises()
  const modal = wrapper.findAll('.arco-modal').find((n) => n.text().includes('编辑章节'))!
  modal.findComponent({ name: 'Switch' }).vm.$emit('update:modelValue', true)
  await flushPromises()
  await modal.get('[data-test="apply-section"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-test="publish-syllabus"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-test="save-syllabus"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toMatchObject({
    revision: 3,
    publishedDate: null,
    sections: [{ enabled: true }, { enabled: false, scope: 'excluded' }],
  })
  wrapper
    .findAllComponents({ name: 'Popconfirm' })
    .find((n) => n.find('[data-test="publish-syllabus"]').exists())!
    .vm.$emit('ok')
  await flushPromises()
  expect(request.mock.lastCall?.[0]).toMatchObject({ method: 'POST', data: { revision: 4 } })
  expect(wrapper.find('[data-test="edit-section-1"]').exists()).toBe(false)
})
it('excluded与不对应考试范围禁止启用，已发布只归档不删除且带revision', async () => {
  const { wrapper } = await render(AdminSyllabusDetailView, `/admin/syllabi/${id}`, () => fixture())
  await wrapper.get('[data-test="edit-section-2"]').trigger('click')
  await flushPromises()
  const modal = wrapper
    .findAllComponents({ name: 'Modal' })
    .find((n) => n.props('title') === '编辑章节')!
  expect(modal.findComponent({ name: 'Switch' }).props('disabled')).toBe(true)
  const { wrapper: published, request } = await render(
    AdminSyllabusDetailView,
    `/admin/syllabi/${id}`,
    () => ({ ...fixture(), status: 'published' }),
  )
  expect(published.find('[data-test="save-syllabus"]').exists()).toBe(false)
  expect(published.text()).not.toContain('删除')
  published
    .findAllComponents({ name: 'Popconfirm' })
    .find((n) => n.find('[data-test="archive-syllabus"]').exists())!
    .vm.$emit('ok')
  await flushPromises()
  expect(request.mock.lastCall?.[0]).toMatchObject({
    method: 'POST',
    url: `/api/v1/admin/syllabi/${id}/archive`,
    data: { revision: 3 },
  })
})
it('个人年份加载保存清空及范围校验，兼容旧偏好不猜年份', async () => {
  const { wrapper, request } = await render(
    ProfileView,
    '/practice/profile',
    () => ({ subject: 'vocational_aptitude' }),
    true,
  )
  const year = wrapper.get('[data-test="exam-year"] input')
  expect(year.element).toHaveProperty('value', '')
  await year.setValue('2027')
  await wrapper.get('[data-test="save-subject"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toEqual({
    subject: 'vocational_aptitude',
    examYear: 2027,
  })
  await year.setValue('')
  await wrapper.get('[data-test="save-subject"]').trigger('click')
  await flushPromises()
  expect(request.mock.lastCall?.[0].data).toEqual({
    subject: 'vocational_aptitude',
    examYear: null,
  })
  request.mockClear()
  await year.setValue('2101')
  await wrapper.get('[data-test="save-subject"]').trigger('click')
  await flushPromises()
  expect(request).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('2000')
})
