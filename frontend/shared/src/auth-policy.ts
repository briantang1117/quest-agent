import type { UserProfile } from './auth'

export function homePath(user: UserProfile): string {
  return user.mustChangePassword
    ? '/change-password'
    : user.role === 'admin'
      ? '/admin/users'
      : '/practice'
}
function allowed(user: UserProfile, path: string) {
  if (user.mustChangePassword) return path === '/change-password'
  return (
    path === '/change-password' ||
    path === '/dev/connection' ||
    (user.role === 'admin'
      ? path === '/admin/users' ||
        path === '/admin/banks' ||
        path === '/admin/syllabi' ||
        // 只放行精确路径：日志详情走接口，不额外开放 /admin/logs/* 子路由。
        path === '/admin/logs' ||
        path === '/admin/events' ||
        path === '/admin/statistics' ||
        /^\/admin\/users\/[1-9][0-9]*\/controls$/.test(path) ||
        /^\/admin\/(?:questions|banks|syllabi)\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
          path,
        )
      : [
          '/practice',
          '/practice/agent',
          '/practice/profile',
          '/practice/syllabus',
          '/practice/materials',
        ].includes(path))
  )
}
// next采用精确白名单，不解析或归一化潜在的外部地址/编码路径。
export function safeNext(user: UserProfile, next: unknown): string {
  return typeof next === 'string' && allowed(user, next) ? next : homePath(user)
}
export function authRedirect(
  user: UserProfile | null,
  path: string,
): null | string | { path: string; query: { next: string } } {
  if (!user || user.status !== 'active')
    return path === '/login' ? null : { path: '/login', query: { next: path } }
  if (user.mustChangePassword) return path === '/change-password' ? null : '/change-password'
  return allowed(user, path) ? null : homePath(user)
}
