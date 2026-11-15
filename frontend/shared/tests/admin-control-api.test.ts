import { expect, it, vi } from 'vitest'
import { createAdminControlApi } from '../src/admin-control-api'
it('用户授权、充值、下线、日志与统计固定鉴权路径和请求体', async () => {
  const request = vi.fn().mockResolvedValue({})
  const api = createAdminControlApi({ request })
  await api.feature('12/3', 'materials', true)
  await api.credit('12', { amount: 100, requestId: 'unique-id' })
  await api.kick('12')
  await api.statistics({ userId: '12', from: '2026-11-01', to: '2026-11-12' })
  await api.events({ userId: '12', kind: 'login', page: 1, size: 20 })
  expect(request.mock.calls[0]?.[0]).toEqual({
    method: 'PATCH',
    url: '/api/v1/admin/users/12%2F3/features/materials',
    data: { enabled: true },
  })
  expect(request.mock.calls[1]?.[0].data.requestId).toBe('unique-id')
  expect(request.mock.calls[2]?.[0].url).toBe('/api/v1/admin/users/12/force-logout')
  expect(request.mock.calls[3]?.[0].params.userId).toBe('12')
  expect(request.mock.calls[4]?.[0].params.kind).toBe('login')
})
