import { isIP } from 'node:net'

interface ProxyRequest {
  setHeader(name: string, value: string): void
  removeHeader(name: string): void
}

// 来源只取当前TCP连接，始终覆盖专用头，绝不从浏览器传入的转发头推断身份。
export function overwriteClientIp(
  proxyRequest: ProxyRequest,
  request: { socket: { remoteAddress?: string } },
) {
  proxyRequest.removeHeader('X-Quest-Client-IP')
  proxyRequest.removeHeader('X-Forwarded-For')
  const address = request.socket.remoteAddress
  if (address && isIP(address)) proxyRequest.setHeader('X-Quest-Client-IP', address)
}
