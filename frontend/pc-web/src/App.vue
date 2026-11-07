<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, homePath, useAuthStore } from '@quest/shared'
import { useConnectionStore } from '@quest/shared/connection'
const auth = useAuthStore()
const connection = useConnectionStore()
const router = useRouter()
const logoutBusy = ref(false)
const logoutError = ref('')
const home = computed(() => (auth.user ? homePath(auth.user) : '/login'))
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  logoutError.value = ''
  connection.clearToken()
  const pending = auth.logout()
  await router.replace('/login')
  try {
    await pending
  } catch (cause) {
    logoutError.value = `本地会话已清除；服务端登出未确认：${errorMessage(cause)}`
  } finally {
    logoutBusy.value = false
  }
}
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="topbar">
    <RouterLink class="brand" :to="home"
      ><span class="brand-mark">序</span
      ><span>知序<span class="brand-sub">独立学习，安心管理</span></span></RouterLink
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
  <a-alert v-if="logoutError" type="warning" role="alert">{{ logoutError }}</a-alert>
  <RouterView />
  <footer class="site-footer">
    <span>知序 · 让学习有迹可循</span
    ><RouterLink v-if="auth.authenticated && !auth.user?.mustChangePassword" to="/dev/connection"
      >开发连接诊断</RouterLink
    >
  </footer>
</template>
