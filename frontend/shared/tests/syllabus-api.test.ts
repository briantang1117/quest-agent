import { expect, it, vi } from 'vitest'
import { createSyllabusApi } from '../src/syllabus-api'
import { createCoachApi } from '../src/coach-api'
import { createApiClient } from '../src/api'

it('考纲API透传筛选、multipart元数据、完整编辑及revision，原件走鉴权blob', async () => {
  const request = vi.fn().mockResolvedValue(null)
  const api = createSyllabusApi({ request })
  await api.list({
    page: 2,
    size: 50,
    keyword: '考纲',
    examType: 'career_a',
    examYear: 2027,
    status: 'draft',
  })
  const file = new File(['%PDF'], '考纲.pdf', { type: 'application/pdf' })
  await api.upload(file, {
    title: '考纲',
    examType: 'career_a',
    examYear: 2027,
    versionLabel: '第一版',
    issuer: '发布机构',
    publishedDate: null,
    sourceUrl: '',
  })
  const body = {
    revision: 3,
    title: '修订',
    versionLabel: '第一版',
    issuer: '发布机构',
    publishedDate: null,
    sourceUrl: '',
    sections: [],
  }
  await api.update('id/1', body)
  await api.publish('id/1', 4)
  await api.archive('id/1', 5)
  await api.file('id/1')
  expect(request.mock.calls[0]![0]).toMatchObject({
    method: 'GET',
    params: {
      page: 2,
      size: 50,
      keyword: '考纲',
      examType: 'career_a',
      examYear: 2027,
      status: 'draft',
    },
  })
  const data = request.mock.calls[1]![0].data as FormData
  expect(data.get('file')).toBe(file)
  expect(Object.fromEntries([...data.entries()].filter(([key]) => key !== 'file'))).toEqual({
    title: '考纲',
    examType: 'career_a',
    examYear: '2027',
    versionLabel: '第一版',
    issuer: '发布机构',
  })
  expect(request.mock.calls.slice(2).map(([c]) => c)).toEqual([
    { method: 'PUT', url: '/api/v1/admin/syllabi/id%2F1', data: body },
    { method: 'POST', url: '/api/v1/admin/syllabi/id%2F1/publish', data: { revision: 4 } },
    { method: 'POST', url: '/api/v1/admin/syllabi/id%2F1/archive', data: { revision: 5 } },
    { method: 'GET', url: '/api/v1/admin/syllabi/id%2F1/file', responseType: 'blob' },
  ])
})
it('个人偏好兼容旧subject-only响应，写入显式可空年份且不改变创建会话请求', async () => {
  const request = vi.fn().mockResolvedValue({ subject: 'vocational_aptitude' })
  const api = createCoachApi({ request })
  expect(await api.preference()).toEqual({ subject: 'vocational_aptitude', examYear: null })
  await api.setPreference('vocational_aptitude', 2027)
  await api.setPreference('administrative_aptitude', null)
  await api.create('r1', 'practice')
  expect(request.mock.calls.slice(1).map(([c]) => c.data)).toEqual([
    { subject: 'vocational_aptitude', examYear: 2027 },
    { subject: 'administrative_aptitude', examYear: null },
    { requestId: 'r1', mode: 'practice' },
  ])
})
it('鉴权HTTP客户端允许成功PDF blob，但拒绝JSON错误或失败blob', async () => {
  const client = createApiClient({ baseURL: '/gateway', getToken: () => 'token' })
  const pdf = new Blob(['%PDF'], { type: 'application/pdf' })
  client.defaults.adapter = async (config) => ({
    data: pdf,
    status: 200,
    statusText: 'OK',
    headers: { 'content-type': 'application/pdf' },
    config,
  })
  expect(await client.request({ url: '/file', responseType: 'blob' })).toBe(pdf)
  client.defaults.adapter = async (config) => ({
    data: new Blob([JSON.stringify({ code: 40900, message: '冲突', data: null, traceId: 't1' })], {
      type: 'application/json',
    }),
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  })
  await expect(client.request({ url: '/file', responseType: 'blob' })).rejects.toMatchObject({
    code: 40900,
    traceId: 't1',
  })
  client.defaults.adapter = async (config) => {
    throw {
      isAxiosError: true,
      config,
      response: {
        status: 401,
        data: new Blob(
          [JSON.stringify({ code: 40100, message: '登录失效', data: null, traceId: 'auth1' })],
          { type: 'application/json' },
        ),
      },
    }
  }
  await expect(client.request({ url: '/file', responseType: 'blob' })).rejects.toMatchObject({
    status: 401,
    code: 40100,
    traceId: 'auth1',
  })
})
