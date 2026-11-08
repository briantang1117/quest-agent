import { computed } from 'vue'
import { useAuthStore } from '@quest/shared'

export function useCacheScope() {
  const auth = useAuthStore()
  return computed(() =>
    JSON.stringify([
      auth.baseURL,
      auth.token,
      auth.user?.id,
      auth.user?.role,
      auth.user?.status,
      auth.user?.mustChangePassword,
    ]),
  )
}
