<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { notifyError, homePath, useAuthStore } from '@quest/shared'
import { useConnectionStore } from '@quest/shared/connection'
const auth = useAuthStore()
const connection = useConnectionStore()
const router = useRouter()
const route = useRoute()
// 首次导航完成前matched为空，不能把尚未恢复的身份当普通用户渲染。
const routeReady = computed(() => route.matched.length > 0)
const adminWorkspace = computed(
  () => auth.user?.role === 'admin' && route.path.startsWith('/admin'),
)
const loginPage = computed(() => ['/login', '/change-password'].includes(route.path))
const logoutBusy = ref(false)
const home = computed(() => (auth.user ? homePath(auth.user) : '/login'))
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  connection.clearToken()
  const pending = auth.logout()
  await router.replace('/login')
  try {
    await pending
  } catch (cause) {
    notifyError(cause)
  } finally {
    logoutBusy.value = false
  }
}
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <div v-if="!routeReady" class="app-loading" role="status" aria-live="polite">正在加载</div>
  <template v-else-if="adminWorkspace"><RouterView /></template>
  <template v-else>
    <header class="topbar" :class="{ 'login-header': loginPage }">
      <RouterLink class="brand" :to="home"
        ><span v-if="!loginPage" class="brand-mark">序</span
        ><span
          >知序<span v-if="!loginPage" class="brand-sub">独立学习，安心管理</span></span
        ></RouterLink
      >
      <nav v-if="auth.authenticated && !auth.user?.mustChangePassword" aria-label="工作空间">
        <RouterLink v-if="auth.user?.role === 'admin'" to="/admin/users">用户管理</RouterLink>
        <template v-else
          ><RouterLink to="/practice">学习工作区</RouterLink
          ><RouterLink to="/practice/agent">个人 Agent</RouterLink></template
        >
      </nav>
      <div v-if="auth.authenticated" class="session-controls">
        <span
          >{{ auth.user?.displayName
          }}<span class="session-role">{{
            auth.user?.role === 'admin' ? '管理员' : '普通用户'
          }}</span></span
        ><RouterLink v-if="!auth.user?.mustChangePassword" class="text-link" to="/change-password"
          >修改密码</RouterLink
        ><a-button data-test="logout" :loading="logoutBusy" @click="logout">退出登录</a-button>
      </div>
    </header>
    <RouterView />
    <footer class="site-footer">
      <span><span class="brand-wordmark">知序</span> · 让学习有迹可循</span
      ><RouterLink v-if="auth.authenticated && !auth.user?.mustChangePassword" to="/dev/connection"
        >开发连接诊断</RouterLink
      >
    </footer>
  </template>
</template>
<style scoped>
.app-loading {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: var(--color-text-3);
  font-size: 14px;
}
</style>
