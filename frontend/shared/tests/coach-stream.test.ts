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

describe('coach turn stream', () => {
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
