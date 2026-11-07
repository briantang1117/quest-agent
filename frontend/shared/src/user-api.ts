import type { useAuthStore, UserProfile, UserStatus } from './auth'
export interface UserPage {
  list: UserProfile[]
  total: number
  page: number
  size: number
}
export interface UserQuery {
  page: number
  size: number
  keyword: string
  status: UserStatus | ''
}
export interface CreateUserInput {
  username: string
  displayName: string
  initialPassword: string
}
export function createUserApi(auth: Pick<ReturnType<typeof useAuthStore>, 'request'>) {
  return {
    list: (params: UserQuery) =>
      auth.request<UserPage>({ method: 'GET', url: '/api/v1/admin/users', params }),
    create: (data: CreateUserInput) =>
      auth.request<UserProfile>({ method: 'POST', url: '/api/v1/admin/users', data }),
    setStatus: (id: string, status: UserStatus) =>
      auth.request<UserProfile>({
        method: 'PATCH',
        url: `/api/v1/admin/users/${encodeURIComponent(id)}/status`,
        data: { status },
      }),
    resetPassword: (id: string, newPassword: string) =>
      auth.request<UserProfile>({
        method: 'POST',
        url: `/api/v1/admin/users/${encodeURIComponent(id)}/reset-password`,
        data: { newPassword },
      }),
  }
}
