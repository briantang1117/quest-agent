import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import ArcoVue from '@arco-design/web-vue'
import { aiAuditParams, useAuthStore } from '@quest/shared'
import { createAppRouter } from '../router'
import AdminAiLogsView from '../views/AdminAiLogsView.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function row(overrides: Record<string, unknown> = {}) {
  return {
    id: '7',
    action: 'ai.coach_turn',
    outcome: 'failed',
    actor: 'alice',
    targetId: null,
    traceId: 'trace-1',
    elapsedMs: 1200,
    attempts: 3,
    model: 'm',
    error: '类型=ServiceError; 耗时=1200ms; 尝试=3次',
    createdAt: '2026-11-08T10:00:00Z',
    ...overrides,
  }
}
async function render(
  responder: (config: { url?: string; method?: string; params?: unknown }) => unknown,
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
    username: 'admin',
    displayName: '管理员',
    role: 'admin',
    status: 'active',
    mustChangePassword: false,
    createdAt: '2026-11-08T00:00:00Z',
    lastLoginAt: null,
  }
  vi.spyOn(auth, 'restoreSession').mockResolvedValue()
  const request = vi.spyOn(auth, 'request').mockImplementation(async (c) => responder(c) as never)
  const router = createAppRouter(createMemoryHistory(), pinia)
  await router.push('/admin/logs')
  await router.isReady()
  const wrapper = mount(AdminAiLogsView, {
    global: { plugins: [pinia, router, ArcoVue], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, request, router }
}

it('列表展示调用结果、耗时与重试次数，不含原文', async () => {
  const { wrapper, request } = await render(() => ({
    list: [row()],
    total: 1,
    page: 1,
    size: 20,
  }))
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ method: 'GET', url: '/api/v1/admin/ai-calls' }),
  )
  const text = wrapper.text()
  expect(text).toContain('陪练对话')
  expect(text).toContain('失败')
  expect(text).toContain('alice')
  expect(text).toContain('1.2 秒')
  expect(text).toContain('3 次')
  expect(text).toContain('共 1 条调用记录')
})

it('空筛选不下发，有筛选才带参数', () => {
  // Arco select 不是原生控件，直接验证参数组装函数，避免依赖 Arco 内部 DOM 结构。
  expect(aiAuditParams({}, 1, 20)).toEqual({
    page: 1,
    size: 20,
    action: undefined,
    outcome: undefined,
  })
  expect(aiAuditParams({ action: 'ai.public_import', outcome: 'failed' }, 2, 50)).toEqual({
    page: 2,
    size: 50,
    action: 'ai.public_import',
    outcome: 'failed',
  })
})

it('重置筛选回到第一页并清空条件', async () => {
  const { wrapper, request } = await render(() => ({ list: [], total: 0, page: 2, size: 20 }))
  await wrapper.get('[data-test="reset-logs"]').trigger('click')
  await flushPromises()
  const last = request.mock.calls.at(-1)![0] as { params: Record<string, unknown> }
  expect(last.params.page).toBe(1)
  expect(last.params.action).toBeUndefined()
  expect(last.params.outcome).toBeUndefined()
})

it('详情按需加载，展示请求原文与模型原始输出', async () => {
  const { wrapper, request } = await render((c) =>
    c.url?.includes('/ai-calls/')
      ? {
          row: row(),
          requestJson: '{"message":"PRIVATE_PROMPT"}',
          responseText: '{"action":"chat","reply":"这是被拒绝的原文"}',
        }
      : { list: [row()], total: 1, page: 1, size: 20 },
  )
  // 列表阶段不应请求详情，避免一次拉回所有对话内容。
  expect(request.mock.calls.some(([c]) => c.url?.includes('/ai-calls/7'))).toBe(false)
  await wrapper.get('[data-test="open-detail"]').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({ method: 'GET', url: '/api/v1/admin/ai-calls/7' }),
  )
  const text = wrapper.text()
  expect(text).toContain('PRIVATE_PROMPT')
  expect(text).toContain('这是被拒绝的原文')
})
