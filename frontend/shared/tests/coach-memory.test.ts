import { expect, it, vi } from 'vitest'
import { createCoachApi } from '../src/coach-api'
it('学习记忆接口编码资源ID，续接只传显式topicId，独立对话保持兼容', async () => {
  const request = vi.fn().mockResolvedValue({})
  const api = createCoachApi({ request })
  await api.create('request', 'chat')
  await api.create('next', 'chat', 'topic')
  await api.updateMemory('session/1', '')
  await api.bindTopic('session/1', null)
  await api.deleteTopic('topic/2')
  expect(request.mock.calls[0]?.[0].data).toEqual({
    requestId: 'request',
    mode: 'chat',
    preview: true,
  })
  expect(request.mock.calls[1]?.[0].data).toEqual({
    requestId: 'next',
    mode: 'chat',
    topicId: 'topic',
    preview: true,
  })
  expect(request.mock.calls[2]?.[0]).toEqual({
    method: 'PUT',
    url: '/api/v1/coach/sessions/session%2F1/memory',
    data: { summary: '' },
  })
  expect(request.mock.calls[3]?.[0].data).toEqual({ topicId: null })
  expect(request.mock.calls[4]?.[0].url).toBe('/api/v1/coach/topics/topic%2F2')
})
