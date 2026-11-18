import { expect, it, vi } from 'vitest'
import { createPrivateQuestionsApi, isPrivateJob } from '../src/index'
it('私有附件通过认证上传，导入决策携带所属会话，原件通过认证下载', async () => {
  const request = vi.fn().mockResolvedValue({})
  const api = createPrivateQuestionsApi({ request })
  const file = new File(['题目'], '题目.txt', { type: 'text/plain' })
  await api.upload(file, 'request')
  const config = request.mock.calls[0]![0]
  expect(config.url).toBe('/api/v1/coach/attachments')
  expect(config.data.get('file')).toBe(file)
  expect(config.data.get('requestId')).toBe('request')
  await api.file('a/1')
  expect(request).toHaveBeenLastCalledWith({
    method: 'GET',
    url: '/api/v1/coach/attachments/a%2F1/file',
    responseType: 'blob',
  })
  await api.decide('i/1', 'session', 'keep_original')
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    url: '/api/v1/study/private-items/i%2F1/decision',
    data: { sessionId: 'session', action: 'keep_original' },
  })
})
it('导入事件拒绝无效的题目结果与负数计数', () => {
  const job = {
    id: 'job',
    session_id: 's',
    status: 'queued',
    items: [],
    warnings: [],
    saved: 0,
    needs_attention: 0,
    duplicates: 0,
    failed: 0,
    source_failures: 0,
  }
  expect(isPrivateJob(job)).toBe(true)
  expect(isPrivateJob({ ...job, saved: -1 })).toBe(false)
  expect(
    isPrivateJob({
      ...job,
      items: [{ id: 'i', ordinal: 1, status: 'ready', result: { candidate: {} } }],
    }),
  ).toBe(false)
})
