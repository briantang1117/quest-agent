import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { useAuthStore, type CoachSession, type LearningTopic } from '@quest/shared'
import CoachMemoryPanel from '../components/CoachMemoryPanel.vue'
const wrappers: VueWrapper[] = []
const topic: LearningTopic = {
  id: 'topic-1',
  subject: 'vocational_aptitude',
  title: '政策概念辨析',
  goal: '区分概念',
  progress: '已讨论平台层级',
  questions: '协同机制',
  plan: '下次比较',
  revision: 2,
  source_session_id: null,
  updated_at: '2026-11-15T00:00:00Z',
}
const session: CoachSession = {
  id: 'session-1',
  subject: 'vocational_aptitude',
  title: '个人对话',
  messages: [],
  currentQuestion: null,
  result: null,
  suggestedKeys: [],
  processing: false,
  memory: { summary: '之前的讨论', updatedAt: '2026-11-15T00:00:00Z' },
  topic,
}
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
  }))
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }) as never)
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function render(value = session) {
  const pinia = createPinia(),
    auth = useAuthStore(pinia)
  const request = vi.spyOn(auth, 'request').mockImplementation(async (config) => {
    if (config.url === '/api/v1/coach/topics') return { list: [topic] } as never
    return { ...value, memory: null } as never
  })
  const wrapper = mount(CoachMemoryPanel, {
    props: { session: value, disabled: false },
    global: { plugins: [pinia, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await wrapper.get('[data-test="learning-memory"]').trigger('click')
  await flushPromises()
  return { wrapper, request }
}
it('可清空会话摘要，通过真实接口更新，不触发模型请求', async () => {
  const { wrapper, request } = await render()
  await wrapper.get('textarea').setValue('')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '保存修改')!
    .trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith({
    method: 'PUT',
    url: '/api/v1/coach/sessions/session-1/memory',
    data: { summary: '' },
  })
  expect(wrapper.emitted('update')?.[0]?.[0]).toMatchObject({ memory: null })
})
it('新对话续接显式选择的主题，不自动发送问题', async () => {
  const { wrapper, request } = await render()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '新对话继续此主题')!
    .trigger('click')
  expect(wrapper.emitted('continue')).toEqual([['topic-1']])
  expect(request).toHaveBeenCalledTimes(1)
})
it('会话切换后迟到的主题列表不能重新打开旧抽屉', async () => {
  const pinia = createPinia(),
    auth = useAuthStore(pinia)
  let resolve!: (value: unknown) => void
  vi.spyOn(auth, 'request').mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done
      }) as never,
  )
  const wrapper = mount(CoachMemoryPanel, {
    props: { session, disabled: false },
    global: { plugins: [pinia, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await wrapper.get('[data-test="learning-memory"]').trigger('click')
  await wrapper.setProps({ session: { ...session, id: 'session-2', topic: null } })
  resolve({ list: [topic] })
  await flushPromises()
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.emitted('update')).toBeUndefined()
})
