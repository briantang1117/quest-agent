<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconDown, IconExport, IconLock } from '@arco-design/web-vue/es/icon'
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
// Vue Router 默认按前缀匹配激活；这里显式按当前路径判断，避免 /practice 在所有子页常亮。
const workspaceLinks = computed(() =>
  auth.user?.role === 'admin'
    ? [{ label: '用户管理', to: '/admin/users' }]
    : [
        { label: '学习工作区', to: '/practice' },
        { label: '个人 Agent', to: '/practice/agent' },
        { label: '个人中心', to: '/practice/profile' },
      ],
)
function isCurrent(path: string) {
  return route.path === path
}
const avatarText = computed(() => Array.from(auth.user?.displayName?.trim() || '学')[0])
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
  <div v-else class="user-shell">
    <header class="topbar" :class="{ 'login-header': loginPage }">
      <RouterLink class="brand" :to="home"
        ><span
          >知序<span v-if="!loginPage" class="brand-sub">独立学习，安心管理</span></span
        ></RouterLink
      >
      <nav v-if="auth.authenticated && !auth.user?.mustChangePassword" aria-label="工作空间">
        <RouterLink
          v-for="item in workspaceLinks"
          :key="item.to"
          :to="item.to"
          :class="{ 'is-current': isCurrent(item.to) }"
          :aria-current="isCurrent(item.to) ? 'page' : undefined"
          >{{ item.label }}</RouterLink
        >
      </nav>
      <div v-if="auth.authenticated" class="session-controls">
        <a-dropdown trigger="click" position="br">
          <button
            class="user-account-trigger"
            data-test="user-account-menu"
            aria-label="账号菜单"
            aria-haspopup="menu"
            :disabled="logoutBusy"
          >
            <a-avatar :size="32">{{ avatarText }}</a-avatar>
            <span class="user-account-name"
              ><span>{{ auth.user?.displayName }}</span
              ><span class="user-account-role">{{
                auth.user?.role === 'admin' ? '管理员' : '普通用户'
              }}</span></span
            >
            <IconDown />
          </button>
          <template #content>
            <a-doption data-test="user-change-password" @click="router.push('/change-password')"
              ><template #icon><IconLock /></template>修改密码</a-doption
            >
            <a-doption data-test="logout" :disabled="logoutBusy" @click="logout"
              ><template #icon><IconExport /></template>退出登录</a-doption
            >
          </template>
        </a-dropdown>
      </div>
    </header>
    <RouterView />
    <footer class="site-footer">
      <span><span class="brand-wordmark">知序</span> · 让学习有迹可循</span
      ><RouterLink v-if="auth.authenticated && !auth.user?.mustChangePassword" to="/dev/connection"
        >开发连接诊断</RouterLink
      >
    </footer>
  </div>
</template>
<style scoped>
.user-account-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 2px;
  padding: 4px 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.user-account-trigger:hover {
  background: var(--color-fill-2);
}
.user-account-trigger .arco-avatar {
  flex-shrink: 0;
  background: rgb(var(--primary-1));
  color: rgb(var(--primary-6));
}
.user-account-name {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.user-account-role {
  color: var(--color-text-3);
  font-size: 12px;
}
.user-account-trigger > .arco-icon {
  margin-left: 8px;
  color: var(--color-text-3);
  font-size: 12px;
}
.app-loading {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: var(--color-text-3);
  font-size: 14px;
}
</style>
