import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { useAuthStore } from '@quest/shared'
import { createAppRouter } from '../router'
import PracticeView from '../views/PracticeView.vue'
import SyllabusGuideView from '../views/SyllabusGuideView.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function render(
  component: typeof PracticeView,
  path: string,
  responder: (c: { url?: string; method?: string; data?: unknown; params?: unknown }) => unknown,
) {
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
    username: 'alice',
    displayName: '甲',
    role: 'student',
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
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request }
}
const syllabus = {
  versionId: '11111111-1111-1111-1111-111111111111',
  examYear: 2027,
  title: '合成考纲',
  versionLabel: '2027版',
  sections: [
    {
      versionId: '11111111-1111-1111-1111-111111111111',
      title: '合成考纲',
      examYear: 2027,
      sectionTitle: '判断推理',
      pageStart: 2,
      pageEnd: 3,
      text: '测查逻辑分析能力',
    },
  ],
}
it('导览只查目标年份，章节携带首句进入Agent', async () => {
  const { wrapper, router, request } = await render(SyllabusGuideView, '/practice/syllabus', (c) =>
    c.url?.endsWith('study-preference')
      ? { subject: 'administrative_aptitude', examYear: 2027 }
      : c.url === '/api/v1/study/syllabus'
        ? syllabus
        : { id: '22222222-2222-2222-2222-222222222222', syllabusVersionId: syllabus.versionId },
  )
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      url: '/api/v1/study/syllabus',
      params: { subject: 'administrative_aptitude', examYear: 2027, keyword: '' },
    }),
  )
  expect(wrapper.text()).toContain('判断推理')
  expect(wrapper.text()).toContain('PDF 第 2—3 页')
  await wrapper.get('[data-test="explain-syllabus-section"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/practice/agent'))
  expect(router.currentRoute.value.query.prompt).toBe(
    '请结合2027年考纲，讲讲“判断推理”主要考什么，以及我该怎么准备。',
  )
})
it('缺年份不请求考纲，缺版本不换成年份其他资料', async () => {
  const unset = await render(SyllabusGuideView, '/practice/syllabus', () => ({
    subject: 'vocational_aptitude',
    examYear: null,
  }))
  expect(
    unset.wrapper.findComponent({ name: 'Empty' }).exists() ||
      unset.wrapper.find('.arco-empty').exists(),
  ).toBe(true)
  expect(unset.request.mock.calls.some(([c]) => c.url === '/api/v1/study/syllabus')).toBe(false)
  const absent = await render(SyllabusGuideView, '/practice/syllabus', (c) =>
    c.url?.endsWith('study-preference')
      ? { subject: 'vocational_aptitude', examYear: 2027 }
      : { versionId: null, examYear: 2027, sections: [] },
  )
  expect(absent.wrapper.text()).toContain('不会自动使用其他年份')
  expect(absent.wrapper.find('[data-test="explain-syllabus-section"]').exists()).toBe(false)
})
it('首页提供纲考纲导览入口，导航不创建模型会话', async () => {
  const { wrapper, router, request } = await render(PracticeView, '/practice', (c) =>
    c.url?.endsWith('study-preference')
      ? { subject: 'vocational_aptitude', examYear: 2027 }
      : { list: [] },
  )
  expect(wrapper.text()).toContain('考纲导览')
  await wrapper.get('[data-test="syllabus-guide-entry"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/syllabus')
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
})
