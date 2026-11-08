import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { useAuthStore } from '@quest/shared'
import { createAppRouter } from '../router'
import PracticeView from '../views/PracticeView.vue'
import ProfileView from '../views/ProfileView.vue'
import AgentView from '../views/AgentView.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
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
// 与后端一致：题目挂在出题的那条消息上，currentQuestion 只是会话级索引。
function session() {
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
async function render(
  component: typeof PracticeView,
  path: string,
  responder: (config: { url?: string; method?: string; data?: unknown }) => unknown,
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
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request }
}
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
      data: { subject: 'vocational_aptitude' },
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
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      url: `/api/v1/coach/sessions/${sessionId}/turns`,
      data: expect.objectContaining({
        action: 'answer',
        questionId: 'q1',
        keys: ['B'],
        requestId: expect.any(String),
      }),
    }),
  )
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
  expect(request.mock.calls.at(-1)![0]).toMatchObject({
    method: 'POST',
    url: `/api/v1/coach/sessions/${sessionId}/turns`,
    data: expect.objectContaining({ action: 'next' }),
  })
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

it('chat 类建议只预填输入框，不替用户直接发送', async () => {
  const chat = {
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
  const { wrapper, request } = await render(
    AgentView,
    `/practice/agent?session=${sessionId}`,
    (c) => (c.url === '/api/v1/coach/sessions' ? { list: [] } : chat),
  )
  const before = request.mock.calls.length
  await wrapper.get('[data-test="quick-chat-再讲讲"]').trigger('click')
  await flushPromises()
  // 没有发请求，只是把话术填进输入框由用户确认。
  expect(request.mock.calls.length).toBe(before)
  const input = wrapper.get('#coach-input textarea')
  expect((input.element as HTMLTextAreaElement).value).toBe('再讲讲这个知识点')
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
