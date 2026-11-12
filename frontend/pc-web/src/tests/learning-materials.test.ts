import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { installErrorFeedback, useAuthStore, type Material } from '@quest/shared'
import { createAppRouter } from '../router'
import LearningMaterialsView from '../views/LearningMaterialsView.vue'
import MaterialCard from '../components/MaterialCard.vue'
import App from '../App.vue'

const wrappers: VueWrapper[] = []
afterEach(async () => {
  wrappers.splice(0).forEach((w) => w.unmount())
  await flushPromises()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
const material: Material = {
  id: 'm/1',
  sessionId: 's/1',
  sourceTitle: '来源对话',
  sourceDeleted: false,
  subject: 'vocational_aptitude',
  title: '复习方法',
  status: 'ready',
  format: 'markdown',
  createdAt: '2026-11-10T00:00:00Z',
  updatedAt: '2026-11-10T00:00:00Z',
  error: null,
}
async function render(
  responder: (c: { url?: string; method?: string; data?: unknown }) => unknown,
  shell = false,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.configure('/gateway')
  auth.token = 'token'
  auth.expiresAt = new Date(Date.now() + 3600000).toISOString()
  auth.user = {
    id: '1',
    username: 'alice',
    displayName: '学员',
    role: 'student',
    status: 'active',
    mustChangePassword: false,
    createdAt: material.createdAt,
    lastLoginAt: null,
  }
  vi.spyOn(auth, 'restoreSession').mockResolvedValue()
  const request = vi.spyOn(auth, 'request').mockImplementation(async (c) => responder(c) as never)
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push('/practice/materials')
  await router.isReady()
  const wrapper = mount(shell ? App : LearningMaterialsView, {
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request }
}
it('顶部学习资料tab与一级route，列表使用共用资料卡', async () => {
  const { wrapper, router } = await render(() => ({ list: [material] }), true)
  expect(router.currentRoute.value.meta.pageDeepth).toBe(1)
  expect(
    wrapper
      .get('nav[aria-label="工作空间"] a[href="/practice/materials"]')
      .attributes('aria-current'),
  ).toBe('page')
  expect(wrapper.findComponent(MaterialCard).exists()).toBe(true)
  expect(wrapper.text()).toContain('事业编 A 类 · 职测')
  expect(wrapper.text()).toContain('来源对话')
  expect(wrapper.find('.material-title .arco-tag').exists()).toBe(false)
})
it('ready按需预览SafeMarkdown，下载鉴权blob并释放URL', async () => {
  const blob = new Blob(['# 方法'], { type: 'text/markdown' })
  const create = vi.fn().mockReturnValue('blob:material')
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const { wrapper, request } = await render((c) => {
    if (c.url?.endsWith('/download')) return blob
    if (c.url === '/api/v1/study/materials') return { list: [material] }
    return {
      ...material,
      markdown: '# 方法\n<script>alert(1)</script>\n[坏链接](javascript:alert(1))',
    }
  })
  await wrapper.get('[data-test="preview-material"]').trigger('click')
  await flushPromises()
  expect(wrapper.findComponent({ name: 'SafeMarkdown' }).exists()).toBe(true)
  expect(wrapper.find('h1').text()).toBe('学习资料')
  expect(wrapper.html()).not.toContain('<script>')
  expect(wrapper.html()).not.toContain('href="javascript:')
  await wrapper.get('[data-test="download-material"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith({
    method: 'GET',
    url: '/api/v1/study/materials/m%2F1/download',
    responseType: 'blob',
  })
  expect(create).toHaveBeenCalledWith(blob)
  expect(click).toHaveBeenCalledOnce()
  expect(revoke).toHaveBeenCalledWith('blob:material')
})
it('PDF下载复用鉴权并保存pdf文件', async () => {
  const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
  const create = vi.fn().mockReturnValue('blob:pdf')
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }))
  let saved = ''
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    saved = this.download
  })
  const { wrapper, request } = await render((c) => {
    if (c.url?.endsWith('/download?format=pdf')) return blob
    return { list: [material] }
  })
  await wrapper.get('[data-test="download-material-pdf"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith({
    method: 'GET',
    url: '/api/v1/study/materials/m%2F1/download?format=pdf',
    responseType: 'blob',
    timeout: 70000,
  })
  expect(saved).toMatch(/\.pdf$/)
  expect(create).toHaveBeenCalledWith(blob)
  expect(click).toHaveBeenCalledOnce()
  expect(revoke).toHaveBeenCalledWith('blob:pdf')
})
it('下载失败沿用全局反馈且不触发保存', async () => {
  const feedback = vi.fn()
  const dispose = installErrorFeedback(feedback)
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  try {
    const { wrapper } = await render((c) => {
      if (c.url?.endsWith('/download')) throw new Error('下载失败')
      return { list: [material] }
    })
    await wrapper.get('[data-test="download-material"]').trigger('click')
    await flushPromises()
    expect(feedback).toHaveBeenCalledOnce()
    expect(click).not.toHaveBeenCalled()
  } finally {
    dispose()
  }
})
it('活动轮询卸载时abort，迟到响应不再请求或回写', async () => {
  vi.useFakeTimers()
  let resolve!: (value: unknown) => void
  const pending = new Promise((done) => {
    resolve = done
  })
  const { wrapper, request } = await render((c) =>
    c.url === '/api/v1/study/materials' ? { list: [{ ...material, status: 'queued' }] } : pending,
  )
  await vi.advanceTimersByTimeAsync(3000)
  const signal = request.mock.calls[1]![0].signal
  expect(signal?.aborted).toBe(false)
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  wrapper.unmount()
  expect(signal?.aborted).toBe(true)
  resolve({ ...material, status: 'ready' })
  await flushPromises()
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(2)
})
it('失败仅人工重试一次并立即切到任务态，源删保留标题禁用跳转', async () => {
  vi.useFakeTimers()
  const { wrapper, request } = await render((c) =>
    c.method === 'POST'
      ? { ...material, status: 'queued', sourceDeleted: true }
      : {
          list: [{ ...material, status: 'failed', sourceDeleted: true, error: '生成失败，请重试' }],
        },
  )
  expect(wrapper.find('a[href*="session="]').exists()).toBe(false)
  expect(wrapper.text()).toContain('来源对话')
  expect(wrapper.text()).toContain('已删除')
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(1)
  const retry = wrapper.get('[data-test="retry-material"]')
  await retry.trigger('click')
  await retry.trigger('click')
  await flushPromises()
  expect(request.mock.calls.filter(([c]) => c.method === 'POST')).toHaveLength(1)
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: '/api/v1/study/materials/m%2F1/retry',
      data: { requestId: expect.any(String) },
    }),
  )
  expect(wrapper.text()).toContain('排队中')
  expect(wrapper.find('[data-test="download-material"]').exists()).toBe(false)
})
it('活动状态仅可见时轮询，ready停止，卸载清理', async () => {
  vi.useFakeTimers()
  let visibility = 'visible'
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(
    () => visibility as DocumentVisibilityState,
  )
  const { wrapper, request } = await render((c) =>
    c.url === '/api/v1/study/materials'
      ? { list: [{ ...material, status: 'queued' }] }
      : { ...material, status: 'ready' },
  )
  visibility = 'hidden'
  document.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(1)
  visibility = 'visible'
  document.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(wrapper.text()).toContain('已完成')
  expect(request).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(2)
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  wrapper.unmount()
  document.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(9000)
  expect(request).toHaveBeenCalledTimes(2)
})
it('轮询错误停止且可人工刷新，不自动重试付费或无限刷新会话', async () => {
  vi.useFakeTimers()
  const feedback = vi.fn()
  const dispose = installErrorFeedback(feedback)
  try {
    const { wrapper, request } = await render((c) => {
      if (c.url === '/api/v1/study/materials') return { list: [{ ...material, status: 'queued' }] }
      throw new Error('网络中断')
    })
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(feedback).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('刷新')
    await vi.advanceTimersByTimeAsync(12000)
    expect(request).toHaveBeenCalledTimes(2)
  } finally {
    dispose()
  }
})
