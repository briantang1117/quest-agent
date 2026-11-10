import { expect, it, vi } from 'vitest'
import { createMaterialsApi } from '../src/index'

it('本人资料API固定路径、编码、请求体与鉴权blob下载', async () => {
  const request = vi.fn().mockResolvedValue({})
  const api = createMaterialsApi({ request })
  await api.create('session/1', { requestId: 'create-1', instructions: '突出方法' })
  await api.list()
  await api.get('material/1')
  await api.retry('material/1', { requestId: 'retry-1' })
  await api.download('material/1')
  expect(request.mock.calls.map(([config]) => config)).toEqual([
    {
      method: 'POST',
      url: '/api/v1/coach/sessions/session%2F1/materials',
      data: { requestId: 'create-1', instructions: '突出方法' },
    },
    { method: 'GET', url: '/api/v1/study/materials' },
    { method: 'GET', url: '/api/v1/study/materials/material%2F1' },
    {
      method: 'POST',
      url: '/api/v1/study/materials/material%2F1/retry',
      data: { requestId: 'retry-1' },
    },
    { method: 'GET', url: '/api/v1/study/materials/material%2F1/download', responseType: 'blob' },
  ])
})
