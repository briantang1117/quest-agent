import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from '../src/api'
import { installErrorFeedback, isErrorNotified, notifyError } from '../src/feedback'

let cleanup: (() => void) | undefined
afterEach(() => cleanup?.())
import { errorMessage } from '../src/auth'

describe('全局反馈', () => {
  it('请求默认通知一次，页面helper对同一错误去重且继续reject', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    const client = createApiClient({ baseURL: '/gateway' })
    client.defaults.adapter = async (config) => ({
      config,
      status: 403,
      statusText: 'Forbidden',
      headers: {},
      data: { code: 40300, message: 'Access denied', data: null, traceId: 'trace-secret' },
    })
    const error = await client.get('/x').catch((cause: unknown) => cause)
    expect(error).toBeInstanceOf(ApiError)
    expect(isErrorNotified(error)).toBe(true)
    notifyError(error)
    expect(show).toHaveBeenCalledTimes(1)
    expect(show.mock.calls[0]?.[0]).toMatchObject({
      kind: 'message',
      message: '暂无权限执行此操作，请联系管理员',
    })
  })
  it('silent请求不通知，页面仍可使用自己的安全文案反馈', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    const client = createApiClient({ baseURL: '/gateway' })
    client.defaults.adapter = async () => {
      throw new Error('offline')
    }
    const error = await client.get('/x', { silent: true }).catch((cause: unknown) => cause)
    expect(show).not.toHaveBeenCalled()
    expect(isErrorNotified(error)).toBe(false)
    notifyError(error, '登录服务暂时不可用，请稍后重试')
    expect(show).toHaveBeenCalledTimes(1)
  })
  it('只有显式业务码规则使用dialog，普通403仍是message', () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show, { 40920: { kind: 'dialog', title: '内容已变更' } })
    notifyError(new ApiError('upstream', 40920))
    notifyError(new ApiError('denied', 40300, 'trace', 403))
    expect(show.mock.calls.map(([feedback]) => feedback.kind)).toEqual(['dialog', 'message'])
    expect(show.mock.calls[0]?.[0].title).toBe('内容已变更')
  })
  it.each(['business', 'http', 'network', 'protocol'] as const)(
    '%s失败均默认通知且不自动重试',
    async (kind) => {
      const show = vi.fn()
      cleanup = installErrorFeedback(show)
      const client = createApiClient({ baseURL: '/gateway' })
      let calls = 0
      client.defaults.adapter = async (config) => {
        calls++
        if (kind === 'network') throw new Error('socket detail')
        if (kind === 'http')
          throw { isAxiosError: true, response: { status: 500, data: '<html>secret</html>' } }
        return {
          config,
          status: 200,
          statusText: 'OK',
          headers: {},
          data:
            kind === 'protocol'
              ? '<html>secret</html>'
              : { code: 40900, message: 'secret', data: null, traceId: 'secret' },
        }
      }
      await expect(client.get('/x')).rejects.toBeInstanceOf(ApiError)
      expect(calls).toBe(1)
      expect(show).toHaveBeenCalledTimes(1)
      expect(JSON.stringify(show.mock.calls)).not.toContain('secret')
    },
  )
  it('请求取消不产生网络错误提示', async () => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    const client = createApiClient({ baseURL: '/gateway' })
    const controller = new AbortController()
    controller.abort()
    await expect(client.get('/x', { signal: controller.signal })).rejects.toMatchObject({
      name: 'CanceledError',
    })
    expect(show).not.toHaveBeenCalled()
  })
})

describe('学习资料错误反馈', () => {
  it.each([
    [41340, 413, '对话超出整理预算，请缩小整理范围后再试'],
    [50341, 503, '学习资料生成尚未启用，请联系管理员'],
    [40940, 409, '资料任务状态已变化，请刷新后操作'],
    [40440, 404, '学习资料或来源对话不存在或已被移除'],
    [50340, 503, '学习资料存储暂不可用，请稍后再试'],
    [50342, 503, '学习资料处理容量已满，请稍后再试'],
  ])('业务码 %i 对 HTTP 与无HTTP状态的SSE均使用固定中文且去重', (code, status, message) => {
    const show = vi.fn()
    cleanup = installErrorFeedback(show)
    for (const httpStatus of [status, undefined]) {
      const error = new ApiError('raw-secret-数据库密码', code, 'private-trace', httpStatus)
      expect(errorMessage(error)).toBe(message)
      notifyError(error)
      notifyError(error)
    }
    expect(show).toHaveBeenCalledTimes(2)
    expect(show.mock.calls.map(([feedback]) => feedback)).toEqual([
      { kind: 'message', message, title: undefined },
      { kind: 'message', message, title: undefined },
    ])
    expect(JSON.stringify(show.mock.calls)).not.toContain('raw-secret')
    expect(JSON.stringify(show.mock.calls)).not.toContain('private-trace')
  })
})

describe('安全的错误文案', () => {
  it('普通403使用友好中文，不包含上游文案或traceId', () => {
    expect(errorMessage(new ApiError('Access denied', 40300, 'secret-trace', 403))).toBe(
      '暂无权限执行此操作，请联系管理员',
    )
  })
  it('未知错误不直接展示上游细节', () => {
    expect(errorMessage(new ApiError('数据库密码：secret', 59999, 'secret-trace', 500))).toBe(
      '服务暂时不可用，请稍后重试',
    )
  })
})
