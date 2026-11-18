import { describe, expect, it, vi } from 'vitest'
import { streamTurn, type CoachSession } from '../src/coach-api'

const session: CoachSession = {
  id: 'session/1',
  subject: 'vocational_aptitude',
  title: '答疑',
  messages: [],
  currentQuestion: null,
  result: null,
  suggestedKeys: [],
  processing: false,
}
const turn = { requestId: 'request-1', action: 'chat' as const, text: '请解释' }
function fetchEvents(events: [string, unknown][]) {
  return vi.fn<typeof fetch>().mockResolvedValue(
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              events
                .map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`)
                .join(''),
            ),
          )
          controller.close()
        },
      }),
      { headers: { 'Content-Type': 'text/event-stream' } },
    ),
  )
}
function options(fetcher: typeof fetch) {
  return {
    token: 'token',
    baseURL: '/gateway/',
    fetcher,
    onState: vi.fn(),
    onDelta: vi.fn(),
    onReset: vi.fn(),
    onDone: vi.fn(),
  }
}

const material = {
  id: 'material-1',
  sessionId: session.id,
  sourceTitle: '答疑',
  sourceDeleted: false,
  subject: 'vocational_aptitude',
  title: '学习资料',
  status: 'queued',
  format: 'markdown',
  createdAt: '2026-11-10T00:00:00Z',
  updatedAt: '2026-11-10T00:00:00Z',
  error: null,
}

describe('coach turn stream', () => {
  it('接受可选资料卡且不混入messages', async () => {
    const value = { ...session, materials: [material] }
    const callbacks = options(fetchEvents([['done', value]]))
    await streamTurn(session.id, turn, callbacks)
    expect(callbacks.onDone).toHaveBeenCalledWith(value)
    expect(value.messages).toEqual([])
  })
  it.each([
    null,
    {},
    [null],
    [{}],
    [{ ...material, status: 'complete' }],
    [{ ...material, sourceDeleted: 'false' }],
    [{ ...material, format: 'pdf' }],
    [{ ...material, subject: 'unknown' }],
    [{ ...material, error: 1 }],
    [{ ...material, markdown: 1 }],
    [{ ...material, createdAt: null }],
    [{ ...material, sessionId: 1 }],
    [{ ...material, title: null }],
  ])('拒绝畸形可选materials %j', async (materials) => {
    const callbacks = options(fetchEvents([['done', { ...session, materials }]]))
    await expect(streamTurn(session.id, turn, callbacks)).rejects.toThrow('无效的陪练会话事件')
    expect(callbacks.onDone).not.toHaveBeenCalled()
  })
  it('一次 POST CoachTurn，分发完整 state/delta/reset/done，done 不解 envelope', async () => {
    const state = { ...session, processing: true }
    const fetcher = fetchEvents([
      ['state', state],
      ['delta', { text: '解释' }],
      ['reset', {}],
      ['done', session],
    ])
    const callbacks = options(fetcher)
    await streamTurn(session.id, turn, callbacks)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]![0]).toBe(
      '/gateway/api/v1/coach/sessions/session%2F1/turns/stream',
    )
    expect(JSON.parse(fetcher.mock.calls[0]![1]!.body as string)).toEqual(turn)
    expect(new Headers(fetcher.mock.calls[0]![1]!.headers).get('Authorization')).toBe(
      'Bearer token',
    )
    expect(callbacks.onState).toHaveBeenCalledWith(state)
    expect(callbacks.onDelta).toHaveBeenCalledWith('解释')
    expect(callbacks.onReset).toHaveBeenCalledOnce()
    expect(callbacks.onDone).toHaveBeenCalledWith(session)
  })
  it.each([
    {},
    { data: session },
    { ...session, processing: true },
    { ...session, messages: [{}] },
    { ...session, id: 'other' },
  ])('拒绝不完整或非权威 done %j', async (value) => {
    const callbacks = options(fetchEvents([['done', value]]))
    await expect(streamTurn(session.id, turn, callbacks)).rejects.toThrow()
    expect(callbacks.onDone).not.toHaveBeenCalled()
  })
  it.each([{}, { text: 1 }, 'broken'])('坏 delta 不得静默吞掉 %j', async (value) => {
    const callbacks = options(
      fetchEvents([
        ['delta', value],
        ['done', session],
      ]),
    )
    await expect(streamTurn(session.id, turn, callbacks)).rejects.toThrow()
    expect(callbacks.onDone).not.toHaveBeenCalled()
  })
  it('没有 done 不能成功且不自动重试', async () => {
    const fetcher = fetchEvents([['delta', { text: 'partial' }]])
    await expect(streamTurn(session.id, turn, options(fetcher))).rejects.toMatchObject({
      name: 'IncompleteStreamError',
    })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('error 变成保留业务码的 ApiError，阻止后续 done', async () => {
    const callbacks = options(
      fetchEvents([
        ['error', { code: 40100, message: '失效', traceId: 'trace' }],
        ['done', session],
      ]),
    )
    await expect(streamTurn(session.id, turn, callbacks)).rejects.toMatchObject({
      name: 'ApiError',
      code: 40100,
      traceId: 'trace',
    })
    expect(callbacks.onDone).not.toHaveBeenCalled()
  })
})

it('分发检索状态并接受消息结构化来源', async () => {
  const value = {
    ...session,
    messages: [
      {
        id: 'm',
        role: 'assistant',
        text: '解释',
        sources: [
          {
            tool: 'search_web',
            title: '公开资料',
            url: 'https://example.com/reference',
            retrieved_at: '2026-11-11T00:00:00Z',
          },
        ],
      },
    ],
  }
  const callbacks = {
    ...options(
      fetchEvents([
        ['tool_status', { tool: 'search_web', status: 'running' }],
        ['done', value],
      ]),
    ),
    onToolStatus: vi.fn(),
  }
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onToolStatus).toHaveBeenCalledWith({ tool: 'search_web', status: 'running' })
  expect(callbacks.onDone).toHaveBeenCalledWith(value)
})

it('拒绝来源危险链接', async () => {
  const value = {
    ...session,
    messages: [
      {
        id: 'm',
        role: 'assistant',
        text: '解释',
        sources: [
          {
            tool: 'search_web',
            title: '危险',
            url: 'javascript:alert(1)',
            retrieved_at: '2026-11-11T00:00:00Z',
          },
        ],
      },
    ],
  }
  await expect(
    streamTurn(session.id, turn, options(fetchEvents([['done', value]]))),
  ).rejects.toThrow()
})

it('接受网页抓取状态和来源', async () => {
  const value = {
    ...session,
    messages: [
      {
        id: 'fetch-message',
        role: 'assistant',
        text: '解释',
        sources: [
          {
            tool: 'fetch_page',
            title: '网页',
            url: 'https://example.com/page',
            retrieved_at: '2026-11-11T00:00:00Z',
          },
        ],
      },
    ],
  }
  const callbacks = {
    ...options(
      fetchEvents([
        ['tool_status', { tool: 'fetch_page', status: 'running' }],
        ['done', value],
      ]),
    ),
    onToolStatus: vi.fn(),
  }
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onToolStatus).toHaveBeenCalledWith({ tool: 'fetch_page', status: 'running' })
  expect(callbacks.onDone).toHaveBeenCalledWith(value)
})

it('分发本地时间工具状态且不将其作为网页来源', async () => {
  const callbacks = {
    ...options(
      fetchEvents([
        ['tool_status', { tool: 'get_current_time', status: 'running' }],
        ['done', session],
      ]),
    ),
    onToolStatus: vi.fn(),
  }
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onToolStatus).toHaveBeenCalledWith({
    tool: 'get_current_time',
    status: 'running',
  })
  const value = {
    ...session,
    messages: [
      {
        id: 'time',
        role: 'assistant',
        text: '日期',
        sources: [
          {
            tool: 'get_current_time',
            title: '本地时间',
            url: 'https://example.com',
            retrieved_at: '2026-11-11T00:00:00Z',
          },
        ],
      },
    ],
  }
  await expect(
    streamTurn(session.id, turn, options(fetchEvents([['done', value]]))),
  ).rejects.toThrow()
})

it('扩展预算后接受更多来源，但仍限制消息来源数量', async () => {
  const source = {
    tool: 'search_web',
    title: '资料',
    url: 'https://example.com',
    retrieved_at: '2026-11-11T00:00:00Z',
  }
  const value = {
    ...session,
    messages: [
      {
        id: 'sources',
        role: 'assistant',
        text: '解释',
        sources: Array.from({ length: 36 }, () => ({ ...source })),
      },
    ],
  }
  const callbacks = options(fetchEvents([['done', value]]))
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onDone).toHaveBeenCalledWith(value)
  const tooMany = {
    ...value,
    messages: [
      { ...value.messages[0]!, sources: Array.from({ length: 97 }, () => ({ ...source })) },
    ],
  }
  await expect(
    streamTurn(session.id, turn, options(fetchEvents([['done', tooMany]]))),
  ).rejects.toThrow()
})
it('保留导入任务所属的AI消息标识，拒绝畸形关联字段', async () => {
  const value = {
    ...session,
    messages: [{ id: 'notice', role: 'assistant', text: '已开始导入', privateImportId: 'job-1' }],
  }
  const callbacks = options(fetchEvents([['done', value]]))
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onDone).toHaveBeenCalledWith(value)
  const invalid = {
    ...value,
    messages: [{ ...value.messages[0], privateImportId: { id: 'job-1' } }],
  }
  await expect(
    streamTurn(session.id, turn, options(fetchEvents([['done', invalid]]))),
  ).rejects.toThrow('无效的陪练会话事件')
})

it('保留失败回复的重试文本，拒绝畸形重试字段', async () => {
  const value = {
    ...session,
    messages: [{ id: 'failed', role: 'assistant', text: '讲解失败', retryText: '请重新讲解' }],
  }
  const callbacks = options(fetchEvents([['done', value]]))
  await streamTurn(session.id, turn, callbacks)
  expect(callbacks.onDone).toHaveBeenCalledWith(value)
  for (const retryText of [true, '', '字'.repeat(2001)]) {
    const invalid = { ...value, messages: [{ ...value.messages[0], retryText }] }
    await expect(
      streamTurn(session.id, turn, options(fetchEvents([['done', invalid]]))),
    ).rejects.toThrow('无效的陪练会话事件')
  }
})
