// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { overwriteClientIp } from '../../proxy-client-ip'

function outgoing() {
  const headers = new Map<string, string>([
    ['x-quest-client-ip', '203.0.113.99'],
    ['x-forwarded-for', '203.0.113.88, 198.51.100.7'],
  ])
  return {
    headers,
    setHeader(name: string, value: string) {
      headers.set(name.toLowerCase(), value)
    },
    removeHeader(name: string) {
      headers.delete(name.toLowerCase())
    },
  }
}
describe('开发代理可信客户端来源', () => {
  it.each(['192.0.2.12', '::1', '::ffff:192.0.2.12'])(
    '覆盖伪造来源头，使用socket单值地址 %s',
    (remoteAddress) => {
      const request = outgoing()
      overwriteClientIp(request, { socket: { remoteAddress } })
      expect(request.headers.get('x-quest-client-ip')).toBe(remoteAddress)
      expect(request.headers.has('x-forwarded-for')).toBe(false)
    },
  )
  it.each([undefined, '', 'invalid', '192.0.2.1, 198.51.100.2', '192.0.2.1\r\nFake: yes'])(
    '缺失或非法socket地址时删除来源头，不回退客户端头 %s',
    (remoteAddress) => {
      const request = outgoing()
      overwriteClientIp(request, { socket: { remoteAddress } })
      expect(request.headers.has('x-quest-client-ip')).toBe(false)
      expect(request.headers.has('x-forwarded-for')).toBe(false)
    },
  )
})
