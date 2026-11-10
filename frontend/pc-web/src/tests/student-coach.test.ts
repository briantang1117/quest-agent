import { afterEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, RouterView } from 'vue-router'
import type { Component } from 'vue'
import ArcoVue from '@arco-design/web-vue'
import { installErrorFeedback, useAuthStore, type CoachSession } from '@quest/shared'
import { createAppRouter } from '../router'
import PracticeView from '../views/PracticeView.vue'
import ProfileView from '../views/ProfileView.vue'
import AgentView from '../views/AgentView.vue'

const wrappers: VueWrapper[] = []
afterEach(async () => {
  wrappers.splice(0).forEach((w) => w.unmount())
  // 取消的fetch和已提交导航要先收尾，再销毁jsdom及恢复认证替身。
  await flushPromises()
  await flushPromises()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
const sessionId = '11111111-1111-1111-1111-111111111111'
const question = {
  id: 'q1',
  type: 'single_choice' as const,
  stemMd: '合成题干',
  options: [
    { key: 'A', textMd: '甲' },
    { key: 'B', textMd: '乙' },
  ],
}
// 纯答疑会话：最后一条带后端 chat 建议。
function chatSession(): CoachSession {
  return {
    ...session(),
    currentQuestion: null,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '这是关于概念的说明。',
        suggestions: [{ action: 'chat', label: '再讲讲', text: '再讲讲这个知识点' }],
      },
    ],
  }
}
// 与后端一致：题目挂在出题的那条消息上，currentQuestion 只是会话级索引。
function session(): CoachSession {
  return {
    id: sessionId,
    subject: 'vocational_aptitude',
    title: '互动刷题',
    messages: [{ id: 'm1', role: 'assistant', text: '来一道题。', question }],
    currentQuestion: question,
    result: null,
    suggestedKeys: [],
    processing: false,
  }
}
function mockStream() {
  let controller: ReadableStreamDefaultController<Uint8Array>
  const cancel = vi.fn()
  const body = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value
    },
    cancel,
  })
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    new Response(body, {
      headers: { 'Content-Type': 'text/event-stream' },
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  return {
    fetcher,
    cancel,
    event(name: string, data: unknown) {
      controller.enqueue(
        new TextEncoder().encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`),
      )
    },
    close() {
      controller.close()
    },
  }
}
async function render(
  component: Component,
  path: string,
  responder: (config: { url?: string; method?: string; data?: unknown }) => unknown,
  realTeleport = false,
) {
  if (!vi.isMockFunction(fetch)) mockStream()
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
    displayName: '学员甲',
    role: 'student',
    status: 'active',
    mustChangePassword: false,
    createdAt: '2026-11-08T00:00:00Z',
    lastLoginAt: null,
  }
  vi.spyOn(auth, 'restoreSession').mockResolvedValue()
  const request = vi.spyOn(auth, 'request').mockImplementation(async (c) => responder(c) as never)
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    ...(realTeleport ? { attachTo: document.body } : {}),
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: !realTeleport } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request, auth }
}
it.each([
  { ids: [sessionId, 'second', 'third'], target: sessionId, next: 'second' },
  { ids: ['first', sessionId], target: sessionId, next: 'first' },
  { ids: [sessionId], target: sessionId, next: undefined },
  { ids: [sessionId, 'second'], target: 'second', next: sessionId },
])('删除对话 $target 后正确选择剩余对话 $next', async ({ ids, target, next }) => {
  let remaining = [...ids]
  const { wrapper, router, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => {
      if (c.method === 'DELETE') {
        remaining = remaining.filter((id) => id !== target)
        return null
      }
      if (c.url === '/api/v1/coach/sessions')
        return {
          list: remaining.map((id) => ({
            id,
            title: `对话-${id}`,
            subject: 'vocational_aptitude',
            updatedAt: '2026-11-10T00:00:00Z',
          })),
        }
      return { ...chatSession(), id: c.url!.split('/').at(-1) }
    },
    true,
  )
  await wrapper.get(`[data-test="session-menu-${target}"]`).trigger('click')
  await flushPromises()
  // 打开菜单不是选择这条对话。
  expect(router.currentRoute.value.query.session).toBe(sessionId)
  const option = new DOMWrapper(document.body).get(`[data-test="delete-session-${target}"]`)
  expect(option.text()).toBe('删除')
  expect(option.find('svg').exists()).toBe(true)
  await option.trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ method: 'DELETE', url: `/api/v1/coach/sessions/${target}` }),
  )
  expect(router.currentRoute.value.query.session).toBe(next)
  expect(wrapper.find(`[data-test="session-menu-${target}"]`).exists()).toBe(false)
  if (!next) expect(wrapper.text()).toContain('说说你想学什么。')
})

it('历史遗留的处理中对话可删除并退出当前会话', async () => {
  let deleted = false
  const { wrapper, request, router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => {
      if (c.method === 'DELETE') {
        deleted = true
        return null
      }
      return c.url === '/api/v1/coach/sessions'
        ? {
            list: deleted
              ? []
              : [
                  {
                    id: sessionId,
                    title: '处理中',
                    subject: 'vocational_aptitude',
                    updatedAt: '2026-11-10T00:00:00Z',
                  },
                ],
          }
        : { ...chatSession(), processing: true }
    },
    true,
  )
  await wrapper.get(`[data-test="session-menu-${sessionId}"]`).trigger('click')
  await flushPromises()
  const option = new DOMWrapper(document.body).get(`[data-test="delete-session-${sessionId}"]`)
  expect(option.classes()).not.toContain('arco-dropdown-option-disabled')
  await option.trigger('click')
  await flushPromises()
  expect(request.mock.calls.some(([c]) => c.method === 'DELETE')).toBe(true)
  expect(router.currentRoute.value.query.session).toBeUndefined()
  expect(wrapper.text()).toContain('说说你想学什么。')
})

it('删除正在流式回答的对话，终止本地流并清除片段后切到下一条', async () => {
  const stream = mockStream()
  let deleted = false
  const { wrapper, router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => {
      if (c.method === 'DELETE') {
        deleted = true
        return null
      }
      if (c.url === '/api/v1/coach/sessions')
        return {
          list: (deleted ? ['second'] : [sessionId, 'second']).map((id) => ({
            id,
            title: `对话-${id}`,
            subject: 'vocational_aptitude',
            updatedAt: '2026-11-10T00:00:00Z',
          })),
        }
      return { ...chatSession(), id: c.url!.split('/').at(-1) }
    },
    true,
  )
  await wrapper.get('#coach-input textarea').setValue('删除前的提问')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  stream.event('delta', { text: '旧回答片段' })
  await flushPromises()
  expect(wrapper.text()).toContain('旧回答片段')
  const signal = stream.fetcher.mock.calls[0]![1]!.signal!
  await wrapper.get(`[data-test="session-menu-${sessionId}"]`).trigger('click')
  await flushPromises()
  await new DOMWrapper(document.body)
    .get(`[data-test="delete-session-${sessionId}"]`)
    .trigger('click')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(router.currentRoute.value.query.session).toBe('second')
  expect(stream.cancel).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).not.toContain('旧回答片段')
  expect(wrapper.get('.coach-messages').text()).toContain('这是关于概念的说明。')
  expect(wrapper.find('.coach-thinking').exists()).toBe(false)
})

it('删除请求未返回时服务端先断流，不再读取即将删除的会话', async () => {
  const stream = mockStream()
  let resolveDelete!: () => void
  const deletion = new Promise<void>((resolve) => {
    resolveDelete = resolve
  })
  let deleted = false
  const { wrapper, router, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => {
      if (c.method === 'DELETE')
        return deletion.then(() => {
          deleted = true
          return null
        })
      if (c.url === '/api/v1/coach/sessions')
        return {
          list: deleted
            ? []
            : [
                {
                  id: sessionId,
                  title: '待删除',
                  subject: 'vocational_aptitude',
                  updatedAt: '2026-11-10T00:00:00Z',
                },
              ],
        }
      return chatSession()
    },
    true,
  )
  await wrapper.get('#coach-input textarea').setValue('测试删除竞态')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await wrapper.get(`[data-test="session-menu-${sessionId}"]`).trigger('click')
  await flushPromises()
  await new DOMWrapper(document.body)
    .get(`[data-test="delete-session-${sessionId}"]`)
    .trigger('click')
  const readsBeforeClose = request.mock.calls.filter(
    ([c]) => c.url === `/api/v1/coach/sessions/${sessionId}` && c.method === 'GET',
  ).length
  try {
    stream.close()
    await flushPromises()
    const readsAfterClose = request.mock.calls.filter(
      ([c]) => c.url === `/api/v1/coach/sessions/${sessionId}` && c.method === 'GET',
    ).length
    expect(readsAfterClose).toBe(readsBeforeClose)
  } finally {
    resolveDelete()
    await flushPromises()
  }
  expect(router.currentRoute.value.query.session).toBeUndefined()
})

it('删除失败保留当前对话和列表，不自动跳转', async () => {
  const { wrapper, router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => {
      if (c.method === 'DELETE') throw new Error('合成删除失败')
      return c.url === '/api/v1/coach/sessions'
        ? {
            list: [
              {
                id: sessionId,
                title: '保留的对话',
                subject: 'vocational_aptitude',
                updatedAt: '2026-11-10T00:00:00Z',
              },
            ],
          }
        : chatSession()
    },
    true,
  )
  await wrapper.get(`[data-test="session-menu-${sessionId}"]`).trigger('click')
  await flushPromises()
  await new DOMWrapper(document.body)
    .get(`[data-test="delete-session-${sessionId}"]`)
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.session).toBe(sessionId)
  expect(wrapper.get('.coach-sessions nav').text()).toContain('保留的对话')
  expect(wrapper.get('.coach-messages').text()).toContain('这是关于概念的说明。')
})

it('经真实布局从考纲进入对话，消费首句后仍展示用户消息与流式回答', async () => {
  const stream = mockStream()
  const versionId = '22222222-2222-2222-2222-222222222222'
  const empty: CoachSession = {
    ...chatSession(),
    messages: [],
    syllabusVersionId: versionId,
  }
  const { wrapper, router } = await render(RouterView, '/practice/syllabus', (c) => {
    if (c.url?.endsWith('study-preference'))
      return { subject: 'vocational_aptitude', examYear: 2027 }
    if (c.url === '/api/v1/study/syllabus')
      return {
        versionId,
        examYear: 2027,
        title: '合成考纲',
        versionLabel: '2027版',
        sections: [
          {
            versionId,
            title: '合成考纲',
            examYear: 2027,
            sectionTitle: '判断推理',
            pageStart: 2,
            pageEnd: 3,
            text: '测查逻辑分析能力',
          },
        ],
      }
    if (c.url === '/api/v1/coach/sessions')
      return c.method === 'POST'
        ? empty
        : {
            list: [
              {
                id: sessionId,
                subject: empty.subject,
                title: '考纲学习对话',
                updatedAt: '2026-11-10T00:00:00Z',
              },
            ],
          }
    if (c.url === `/api/v1/coach/sessions/${sessionId}`) return empty
    throw new Error(`未预期请求：${c.method} ${c.url}`)
  })
  await vi.waitFor(() =>
    expect(wrapper.find('[data-test="explain-syllabus-section"]').exists()).toBe(true),
  )
  await wrapper.get('[data-test="explain-syllabus-section"]').trigger('click')
  await vi.waitFor(() => expect(stream.fetcher).toHaveBeenCalledTimes(1))
  await flushPromises()
  const prompt = '请结合2027年考纲，讲讲“判断推理”主要考什么，以及我该怎么准备。'
  expect(router.currentRoute.value.query.prompt).toBeUndefined()
  expect(wrapper.get('.coach-messages').text()).toContain(prompt)
  // SSE 保持未完成时，历史加载已结束，左侧列表也应独立显示。
  expect(wrapper.find('.coach-loading-veil').exists()).toBe(false)
  expect(wrapper.get('.coach-sessions nav').text()).toContain('考纲学习对话')
  expect(wrapper.get('.coach-thinking').text()).toBe('知序正在思考…')
  const agent = wrapper.findComponent(AgentView).element
  const persisted: CoachSession = {
    ...empty,
    processing: true,
    messages: [{ id: 'user-1', role: 'user', text: prompt }],
  }
  stream.event('state', persisted)
  stream.event('delta', { text: '判断推理重点考查逻辑分析能力。' })
  await flushPromises()
  expect(wrapper.findComponent(AgentView).element).toBe(agent)
  expect(wrapper.get('.coach-messages').text()).toContain(prompt)
  expect(wrapper.get('.is-streaming').text()).toContain('判断推理重点考查逻辑分析能力。')
  stream.event('done', {
    ...persisted,
    processing: false,
    messages: [
      ...persisted.messages,
      { id: 'assistant-1', role: 'assistant', text: '考纲讲解已完成。' },
    ],
  })
  stream.close()
  await flushPromises()
  expect(wrapper.get('.coach-messages').text()).toContain('考纲讲解已完成。')
  expect(wrapper.find('.is-streaming').exists()).toBe(false)
  expect(wrapper.find('.coach-thinking').exists()).toBe(false)
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
})

it('会话详情尚未返回时，左侧列表仍能独立显示', async () => {
  let resolveDetail!: (value: CoachSession) => void
  const detail = new Promise<CoachSession>((resolve) => {
    resolveDetail = resolve
  })
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions'
      ? {
          list: [
            {
              id: sessionId,
              subject: 'vocational_aptitude',
              title: '已有学习对话',
              updatedAt: '2026-11-10T00:00:00Z',
            },
          ],
        }
      : detail,
  )
  try {
    expect(wrapper.get('.coach-sessions nav').text()).toContain('已有学习对话')
    expect(wrapper.find('.coach-loading-veil').exists()).toBe(true)
  } finally {
    resolveDetail(chatSession())
    await flushPromises()
  }
  expect(wrapper.find('.coach-loading-veil').exists()).toBe(false)
  expect(wrapper.get('.coach-messages').text()).toContain('这是关于概念的说明。')
})

it('真实布局仅忽略一次性prompt，切换session仍更换Agent实例', async () => {
  const { wrapper, router } = await render(
    RouterView,
    `/practice/agent?session=${sessionId}&prompt=`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : chatSession()),
  )
  await vi.waitFor(() => expect(wrapper.findComponent(AgentView).exists()).toBe(true))
  await flushPromises()
  const agent = wrapper.findComponent(AgentView).element
  await router.replace({ path: '/practice/agent', query: { session: sessionId } })
  await flushPromises()
  expect(wrapper.findComponent(AgentView).element).toBe(agent)
  await router.push({ path: '/practice/agent', query: { session: 'another-session' } })
  await flushPromises()
  expect(wrapper.findComponent(AgentView).element).not.toBe(agent)
})

it('考纲章节首句只发送一次并消费query，刷新不重发', async () => {
  const stream = mockStream()
  const { router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}&prompt=${encodeURIComponent('请讲讲判断推理的考查要求')}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : session()),
  )
  await vi.waitFor(() => expect(stream.fetcher).toHaveBeenCalledTimes(1))
  expect(router.currentRoute.value.query.prompt).toBeUndefined()
  expect(JSON.parse(stream.fetcher.mock.calls[0][1]!.body as string).text).toBe(
    '请讲讲判断推理的考查要求',
  )
  stream.event('done', session())
  await flushPromises()
  await router.replace({ path: '/practice/agent', query: { session: sessionId } })
  await flushPromises()
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
})

it('章节首句发送后清理query不会中断本次请求', async () => {
  const stream = mockStream()
  const { wrapper } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}&prompt=${encodeURIComponent('请讲讲判断推理的考查要求')}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : session()),
  )
  await vi.waitFor(() => expect(stream.fetcher).toHaveBeenCalledTimes(1))
  // 清理query会触发路由watch；不能因此abort刚发出的SSE。
  await flushPromises()
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
  stream.event('delta', { text: '判断推理' })
  await flushPromises()
  expect(wrapper.text()).toContain('判断推理')
  expect(stream.cancel).not.toHaveBeenCalled()
})

it('用户中途离开页面时让后端继续完成，不把回答打断成失败', async () => {
  const stream = mockStream()
  const { wrapper } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}&prompt=${encodeURIComponent('请讲讲判断推理')}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : session()),
  )
  await vi.waitFor(() => expect(stream.fetcher).toHaveBeenCalledTimes(1))
  // 模拟用户点返回：组件卸载。
  wrapper.unmount()
  await flushPromises()
  // 回答已经发出并保存中，不应主动中断；刷新后可看到结果。
  expect(stream.cancel).not.toHaveBeenCalled()
})

it('输入框回车发送，Shift+回车换行', async () => {
  const stream = mockStream()
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : chatSession(),
  )
  const box = wrapper.get('#coach-input textarea')
  await box.setValue('回车发送的消息')
  // 普通回车：提交，不插入换行。
  await box.trigger('keydown', { key: 'Enter' })
  await flushPromises()
  await vi.waitFor(() => expect(stream.fetcher).toHaveBeenCalledTimes(1))
  const body = JSON.parse(stream.fetcher.mock.calls[0][1]!.body as string)
  expect(body.text).toBe('回车发送的消息')

  // Shift+回车：保留换行，不发送。
  const calls = stream.fetcher.mock.calls.length
  await box.setValue('第一行')
  await box.trigger('keydown', { key: 'Enter', shiftKey: true })
  await flushPromises()
  expect(stream.fetcher.mock.calls.length).toBe(calls)
})

it('输入法组词时的回车不误发送', async () => {
  const stream = mockStream()
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : chatSession(),
  )
  const box = wrapper.get('#coach-input textarea')
  await box.setValue('zhong')
  // 拼音选词确认键：isComposing 为真时必须交给输入法。
  await box.trigger('keydown', { key: 'Enter', isComposing: true })
  await flushPromises()
  expect(stream.fetcher).not.toHaveBeenCalled()
})

it('个人中心保存单选科目到后端而非仅本地缓存', async () => {
  const { wrapper, request } = await render(ProfileView, '/practice/profile', (c) =>
    c.method === 'PUT' ? { subject: 'vocational_aptitude' } : { subject: null },
  )
  await wrapper.get('input[type="radio"][value="vocational_aptitude"]').setValue(true)
  await wrapper.get('[data-test="save-subject"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'PUT',
      url: '/api/v1/me/study-preference',
      data: { subject: 'vocational_aptitude', examYear: null },
    }),
  )
})
it('学习入口按后端科目创建一次首句并跳转会话，不要求选试卷', async () => {
  const { wrapper, router, request } = await render(PracticeView, '/practice', (c) =>
    c.url?.endsWith('study-preference')
      ? { subject: 'vocational_aptitude' }
      : c.method === 'POST'
        ? session()
        : { list: [] },
  )
  await wrapper.get('[data-test="start-practice"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: '/api/v1/coach/sessions',
      data: expect.objectContaining({ mode: 'practice', requestId: expect.any(String) }),
    }),
  )
  expect(router.currentRoute.value.path).toBe('/practice/agent')
  expect(router.currentRoute.value.query.session).toBe(sessionId)
  expect(wrapper.text()).not.toContain('选择试卷')
})
it('聊天刷新只读取会话，不重复发送快捷首句；卡片提交携带题目ID', async () => {
  const { wrapper, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : session()),
  )
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  expect(wrapper.text()).toContain('合成题干')
  expect(wrapper.text()).not.toContain('正确答案')
  await wrapper.get('input[type="radio"][value="B"]').setValue(true)
  await wrapper.get('[data-test="submit-answer"]').trigger('click')
  await flushPromises()
  const fetcher = vi.mocked(fetch)
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(fetcher.mock.calls[0]![0]).toBe(`/gateway/api/v1/coach/sessions/${sessionId}/turns/stream`)
  expect(JSON.parse(fetcher.mock.calls[0]![1]!.body as string)).toEqual(
    expect.objectContaining({
      action: 'answer',
      questionId: 'q1',
      keys: ['B'],
      requestId: expect.any(String),
    }),
  )
  expect(wrapper.get('.is-pending').text()).toContain('我的答案：B')
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
})
it('追问后题目卡片仍内联在出题那条消息里，不在底部重复渲染', async () => {
  const answered = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: {
          correct: false,
          selectedKeys: ['A'],
          answerKeys: ['B'],
          explanationMd: '原解析',
        },
      },
      { id: 'm2', role: 'user', text: '我的答案：A' },
      { id: 'm3', role: 'assistant', text: '这次回答不正确。' },
      { id: 'm4', role: 'user', text: '邯郸学步是什么意思？' },
      { id: 'm5', role: 'assistant', text: '比喻生硬模仿。' },
    ],
    result: {
      correct: false,
      selectedKeys: ['A'],
      answerKeys: ['B'],
      explanationMd: '原解析',
    },
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : answered,
  )
  const articles = wrapper.findAll('.coach-message')
  const cards = wrapper.findAll('.coach-question')
  // 只有一张卡片，且挂在第一条（出题的那条）消息内部，而不是浮在最下方。
  expect(cards).toHaveLength(1)
  expect(cards[0].element.closest('.coach-message')).toBe(articles[0].element)
  expect(articles.at(-1)!.text()).toContain('比喻生硬模仿')
  // 判分结果跟着题目走，不留在底部。
  expect(cards[0].text()).toContain('正确答案：B')
})
it('展示态不给交互控件，避免禁用变灰看不清', async () => {
  const answered = {
    ...session(),
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: true, selectedKeys: ['B'], answerKeys: ['B'], explanationMd: '原解析' },
      },
      { id: 'm2', role: 'user', text: '我的答案：B' },
      { id: 'm3', role: 'assistant', text: '回答正确。' },
      { id: 'm4', role: 'user', text: '再讲讲这个知识点' },
      { id: 'm5', role: 'assistant', text: '好的。' },
    ],
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : answered,
  )
  const card = wrapper.get('.coach-question')
  // 展示态用纯文本列表渲染选项，不再有 radio/checkbox 组件。
  expect(card.find('input[type="radio"]').exists()).toBe(false)
  expect(card.find('input[type="checkbox"]').exists()).toBe(false)
  const mine = card.get('[data-option="B"]')
  expect(mine.classes()).toContain('is-selected')
  expect(mine.classes()).toContain('is-correct')
  // 我选的 B 正好正确；干扰项 A 不应被标成正确。
  expect(card.get('[data-option="A"]').classes()).not.toContain('is-correct')
})

it('答完再追问后当前题自动转为展示态，不再可作答', async () => {
  const graded = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: false, selectedKeys: ['A'], answerKeys: ['B'], explanationMd: '原解析' },
      },
      { id: 'm2', role: 'user', text: '我的答案：A' },
      { id: 'm3', role: 'assistant', text: '这次回答不正确。' },
      { id: 'm4', role: 'user', text: '再讲讲这个词' },
      { id: 'm5', role: 'assistant', text: '好的。' },
    ],
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : graded,
  )
  // 必须是“当前题”：确认这条用例真的渲染了卡片，而不是空断言蒙混过关。
  expect(wrapper.find('.coach-question').exists()).toBe(true)
  const card = wrapper.get('.coach-question')
  expect(card.find('[data-test="submit-answer"]').exists()).toBe(false)
  expect(card.find('input[type="radio"]').exists()).toBe(false)
  expect(card.get('[data-option="A"]').classes()).toContain('is-wrong')
})
it('交互态用 #radio 插槽自绘卡片，去掉 Arco 自带圆圈', async () => {
  const interactive = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : session(),
  )
  const root = interactive.wrapper.get('.coach-question')
  // 仍是真 radio（可点、可键盘操作）。
  expect(root.find('input[type="radio"]').exists()).toBe(true)
  expect(root.findAll('.option-card')).toHaveLength(2)
  // 关键：自绘卡片后不再渲染 Arco 默认的圆圈图标。
  expect(root.find('.arco-radio-icon').exists()).toBe(false)
  expect(root.find('.arco-radio-icon-hover').exists()).toBe(false)
  interactive.wrapper.unmount()

  const review = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions'
      ? { list: [] }
      : {
          ...session(),
          currentQuestion: question,
          messages: [
            {
              id: 'm1',
              role: 'assistant',
              text: '来一道题。',
              question,
              result: {
                correct: false,
                selectedKeys: ['A'],
                answerKeys: ['B'],
                explanationMd: '解析',
              },
            },
          ],
        },
  )
  // 只读态：同样的卡片结构，但没有可点的表单控件。
  expect(review.wrapper.findAll('.coach-question .option-card')).toHaveLength(2)
  expect(review.wrapper.find('.coach-question input[type="radio"]').exists()).toBe(false)
})

it('只读态按对错统一着色：选错红色、选对绿色', async () => {
  const wrong = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: false, selectedKeys: ['A'], answerKeys: ['B'], explanationMd: '解析' },
      },
    ],
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : wrong,
  )
  // 我选的 A 是错的 -> 红色；正确答案 B -> 绿色。
  const mineWrong = wrapper.get('[data-option="A"]')
  expect(mineWrong.classes()).toContain('option-card')
  expect(mineWrong.classes()).toContain('is-wrong')
  expect(wrapper.get('[data-option="B"]').classes()).toContain('is-correct')
  // 标签必须落在状态卡片内部，才能继承对应的红/绿，不会出现绿底青字。
  expect(mineWrong.get('.option-flag').text()).toBe('我的选择')
})

it('选对时"我的选择"标签在绿色卡片内，不残留主题色', async () => {
  const right = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: true, selectedKeys: ['B'], answerKeys: ['B'], explanationMd: '解析' },
      },
    ],
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : right,
  )
  const chosen = wrapper.get('[data-option="B"]')
  expect(chosen.classes()).toContain('is-correct')
  expect(chosen.get('.option-flag').text()).toBe('我的选择')
  // 答对时不再重复标注"正确答案"，避免同一个标签出现两次。
  expect(chosen.findAll('.option-flag')).toHaveLength(1)
})
it('快捷建议由后端给出，前端循环渲染在对话末尾', async () => {
  const graded = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: false, selectedKeys: ['A'], answerKeys: ['B'], explanationMd: '解析' },
      },
      { id: 'm2', role: 'user', text: '我的答案：A' },
      {
        id: 'm3',
        role: 'assistant',
        text: '这次回答不正确。讲解如下……',
        suggestions: [
          { action: 'next', label: '继续下一题' },
          { action: 'chat', label: '再讲讲', text: '再讲讲这个知识点' },
        ],
      },
    ],
  }
  const { wrapper, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : graded),
  )
  const actions = wrapper.find('.coach-quick-actions')
  expect(actions.exists()).toBe(true)
  // 后端给几条就渲染几条，文案也完全来自后端。
  expect(actions.findAll('button')).toHaveLength(2)
  expect(actions.text()).toContain('继续下一题')
  expect(actions.text()).toContain('再讲讲')
  // 必须是最后一条消息的下一个兄弟节点：写进卡片或别处都算回归。
  const articles = wrapper.findAll('.coach-message')
  expect(articles.at(-1)!.element.nextElementSibling).toBe(actions.element)
  expect(articles.at(-1)!.element.contains(actions.element)).toBe(false)
  await wrapper.get('[data-test="quick-next-继续下一题"]').trigger('click')
  await flushPromises()
  expect(JSON.parse(vi.mocked(fetch).mock.calls[0]![1]!.body as string).action).toBe('next')
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
})

it('没有后端建议时不渲染快捷区，前端不自造按钮', async () => {
  const graded = {
    ...session(),
    currentQuestion: question,
    messages: [
      {
        id: 'm1',
        role: 'assistant',
        text: '来一道题。',
        question,
        result: { correct: true, selectedKeys: ['B'], answerKeys: ['B'], explanationMd: '解析' },
      },
      { id: 'm2', role: 'assistant', text: '讲解完毕。' },
    ],
  }
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : graded,
  )
  expect(wrapper.find('.coach-quick-actions').exists()).toBe(false)
})

it('chat 建议按用户原意直接发送，普通聊天也逐段显示且 done 保留权威建议', async () => {
  const stream = mockStream()
  let saved = chatSession()
  const { wrapper, router, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : saved),
  )
  await wrapper.get('[data-test="quick-chat-再讲讲"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('.is-pending').text()).toContain('再讲讲这个知识点')
  expect(JSON.parse(stream.fetcher.mock.calls[0]![1]!.body as string)).toMatchObject({
    action: 'chat',
    text: '再讲讲这个知识点',
  })
  const state: CoachSession = {
    ...chatSession(),
    processing: true,
    messages: [...chatSession().messages, { id: 'u1', role: 'user', text: '再讲讲这个知识点' }],
  }
  stream.event('state', state)
  await flushPromises()
  expect(wrapper.find('.is-pending').exists()).toBe(false)
  stream.event('delta', { text: '宏观' })
  await flushPromises()
  expect(wrapper.get('.is-streaming').text()).toContain('宏观')
  stream.event('delta', { text: '经济' })
  await flushPromises()
  expect(wrapper.get('.is-streaming').text()).toContain('宏观经济')
  stream.event('suggestions', { items: [{ action: 'chat', label: '临时建议', text: '临时' }] })
  saved = {
    ...chatSession(),
    messages: [
      ...state.messages,
      {
        id: 'a1',
        role: 'assistant',
        text: '宏观经济，权威持久化讲解',
        suggestions: [{ action: 'chat', label: '继续聊', text: '详细聊聊' }],
      },
    ],
  }
  stream.event('done', saved)
  await flushPromises()
  expect(wrapper.find('.is-streaming').exists()).toBe(false)
  expect(wrapper.text()).toContain('宏观经济，权威持久化讲解')
  expect(wrapper.get('.coach-quick-actions').text()).toBe('继续聊')
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
  await router.push('/practice/agent')
  await router.push(`/practice/agent?session=${sessionId}`)
  await flushPromises()
  expect(wrapper.text()).toContain('宏观经济，权威持久化讲解')
  expect(wrapper.get('.coach-quick-actions').text()).toBe('继续聊')
})

it('判分 state 先展示、reset 清理半截，done 只用持久化降级结果且不二次 explain', async () => {
  const stream = mockStream()
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : session(),
  )
  await wrapper.get('input[type="radio"][value="B"]').setValue(true)
  await wrapper.get('[data-test="submit-answer"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('.is-pending').text()).toContain('我的答案：B')
  const graded = {
    ...session(),
    processing: true,
    messages: [
      {
        ...session().messages[0],
        result: { correct: true, selectedKeys: ['B'], answerKeys: ['B'], explanationMd: '原解析' },
      },
      { id: 'u1', role: 'user', text: '我的答案：B' },
    ],
  }
  stream.event('state', graded)
  stream.event('delta', { text: '不完整讲解' })
  await flushPromises()
  expect(wrapper.text()).toContain('正确答案：B')
  expect(wrapper.find('.is-pending').exists()).toBe(false)
  stream.event('reset', {})
  await flushPromises()
  expect(wrapper.find('.is-streaming').exists()).toBe(false)
  stream.event('done', {
    ...graded,
    processing: false,
    messages: [
      ...graded.messages,
      {
        id: 'a1',
        role: 'assistant',
        text: '已判分，暂时无法生成讲解',
        suggestions: [{ action: 'next', label: '下一题' }],
      },
    ],
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('不完整讲解')
  expect(wrapper.text()).toContain('暂时无法生成讲解')
  expect(wrapper.get('.coach-quick-actions').text()).toBe('下一题')
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
  expect(String(stream.fetcher.mock.calls[0]![0])).not.toContain('/explain')
})

it('断流只 GET 恢复已保存状态，不重发 POST，不伪造成功且回填未确认原文', async () => {
  const stream = mockStream()
  let reads = 0
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) => {
    if (c.url === '/api/v1/coach/sessions') return { list: [] }
    reads++
    return chatSession()
  })
  await wrapper.get('#coach-input textarea').setValue('我的原文')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  expect(wrapper.get('.is-pending').text()).toContain('我的原文')
  stream.event('delta', { text: '半截' })
  stream.close()
  await flushPromises()
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
  expect(reads).toBe(2)
  expect(wrapper.find('.is-pending').exists()).toBe(false)
  expect(wrapper.find('.is-streaming').exists()).toBe(false)
  expect((wrapper.get('#coach-input textarea').element as HTMLTextAreaElement).value).toBe(
    '我的原文',
  )
  expect(wrapper.text()).not.toContain('半截')
  expect(wrapper.text()).not.toContain('知序正在思考')
})

it('断流 GET 恢复服务端已保存判分和 processing，不把失败当成功', async () => {
  const stream = mockStream()
  let saved: unknown = session()
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : saved,
  )
  await wrapper.get('input[type="radio"][value="B"]').setValue(true)
  await wrapper.get('[data-test="submit-answer"]').trigger('click')
  await flushPromises()
  saved = {
    ...session(),
    processing: true,
    messages: [
      {
        ...session().messages[0],
        result: { correct: true, selectedKeys: ['B'], answerKeys: ['B'], explanationMd: '原解析' },
      },
      { id: 'u1', role: 'user', text: '我的答案：B' },
    ],
  }
  stream.close()
  await flushPromises()
  expect(wrapper.text()).toContain('正确答案：B')
  expect(wrapper.text()).toContain('上一条消息仍在处理中')
  expect(stream.fetcher).toHaveBeenCalledTimes(1)
})

it('切换时 abort，旧失败和 finally 不污染新会话、新请求或草稿', async () => {
  let rejectOld!: (error: Error) => void
  const fetcher = vi.fn<typeof fetch>().mockImplementationOnce(
    () =>
      new Promise((_, reject) => {
        rejectOld = reject
      }),
  )
  vi.stubGlobal('fetch', fetcher)
  const other = 'other-session'
  const { wrapper, router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) =>
      c.url === '/api/v1/coach/sessions'
        ? { list: [] }
        : { ...chatSession(), id: c.url?.endsWith(other) ? other : sessionId },
  )
  await wrapper.get('#coach-input textarea').setValue('旧话')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  const oldSignal = fetcher.mock.calls[0]![1]!.signal!
  await router.push(`/practice/agent?session=${other}`)
  await flushPromises()
  expect(oldSignal.aborted).toBe(true)
  expect(wrapper.find('.is-pending').exists()).toBe(false)
  expect((wrapper.get('#coach-input textarea').element as HTMLTextAreaElement).value).toBe('')
  const stream = mockStream()
  await wrapper.get('#coach-input textarea').setValue('新话')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  rejectOld(new Error('late network failure'))
  await flushPromises()
  expect(wrapper.get('.is-pending').text()).toContain('新话')
  expect(wrapper.text()).toContain('知序正在思考')
  expect((wrapper.get('#coach-input textarea').element as HTMLTextAreaElement).value).toBe('')
  const signal = stream.fetcher.mock.calls[0]![1]!.signal!
  await router.push('/practice/profile')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(wrapper.find('.is-pending').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('知序正在思考')
})

it('切换会话取消旧流避免串数据，但卸载页面不打断作答', async () => {
  const old = mockStream()
  const { wrapper, router } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) =>
      c.url === '/api/v1/coach/sessions'
        ? { list: [] }
        : { ...chatSession(), id: c.url?.endsWith('second') ? 'second' : sessionId },
  )
  await wrapper.get('#coach-input textarea').setValue('旧请求')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  old.event('delta', { text: '旧讲解' })
  await flushPromises()
  expect(wrapper.get('.is-streaming').text()).toContain('旧讲解')
  await router.push('/practice/agent?session=second')
  await flushPromises()
  expect(old.cancel).toHaveBeenCalledOnce()
  expect(wrapper.text()).not.toContain('旧讲解')
  // 卸载页面不中断已发出的回答：后端会继续生成并落库，用户返回后可见。
  const next = mockStream()
  await wrapper.get('#coach-input textarea').setValue('新请求')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  const signal = next.fetcher.mock.calls[0]![1]!.signal!
  wrapper.unmount()
  await flushPromises()
  expect(signal.aborted).toBe(false)
  expect(next.cancel).not.toHaveBeenCalled()
})

it('delta 只在近底时跟随滚动，不抢用户向上阅读的位置', async () => {
  const stream = mockStream()
  const { wrapper } = await render(AgentView, `/practice/agent?session=${sessionId}`, (c) =>
    c.url === '/api/v1/coach/sessions' ? { list: [] } : chatSession(),
  )
  await wrapper.get('#coach-input textarea').setValue('请解释')
  await wrapper.get('form.coach-composer').trigger('submit')
  await flushPromises()
  const element = wrapper.get('.coach-messages').element as HTMLElement
  const scrollTo = vi.fn()
  Object.defineProperties(element, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 300 },
    scrollTo: { configurable: true, value: scrollTo },
  })
  element.scrollTop = 0
  stream.event('delta', { text: '第一段' })
  await flushPromises()
  expect(scrollTo).not.toHaveBeenCalled()
  element.scrollTop = 690
  stream.event('delta', { text: '第二段' })
  await flushPromises()
  expect(scrollTo).toHaveBeenCalledWith({ top: 1000, behavior: 'auto' })
})

it('流式 HTTP401 沿用认证清理与固定提示，不泄露 trace', async () => {
  const feedback = vi.fn()
  const dispose = installErrorFeedback(feedback)
  try {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 40100,
            message: '秘密内容',
            traceId: 'private-trace',
            data: null,
          }),
          { status: 401 },
        ),
      ),
    )
    const { wrapper, auth } = await render(
      AgentView,
      `/practice/agent?session=${sessionId}`,
      (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : chatSession()),
    )
    await wrapper.get('#coach-input textarea').setValue('请解释')
    await wrapper.get('form.coach-composer').trigger('submit')
    await flushPromises()
    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
    expect(feedback).toHaveBeenCalledWith(
      expect.objectContaining({ message: '登录已失效，请重新登录' }),
    )
    expect(JSON.stringify(feedback.mock.calls)).not.toContain('private-trace')
  } finally {
    dispose()
  }
})

it('未选科目时引导个人中心，不创建假会话', async () => {
  const { wrapper, request } = await render(PracticeView, '/practice', () => ({
    subject: null,
    list: [],
  }))
  expect(wrapper.get('[data-test="start-practice"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('选择学习科目')
  expect(request.mock.calls.some(([c]) => c.method === 'POST')).toBe(false)
})
