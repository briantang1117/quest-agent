<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IconUserGroup,
  IconDown,
  IconLock,
  IconExport,
  IconMenuFold,
  IconMenuUnfold,
} from '@arco-design/web-vue/es/icon'
import { errorMessage, useAuthStore } from '@quest/shared'
import './admin-layout.css'
const auth = useAuthStore()
const router = useRouter()
// 宽度与组件折叠状态只由这一处驱动，不能再用媒体查询单独压窄侧栏。
const narrowScreen = ref(window.innerWidth <= 700)
const desktopCollapsed = ref(false)
const collapsed = ref(narrowScreen.value)
function syncViewport() {
  const narrow = window.innerWidth <= 700
  if (narrow === narrowScreen.value) return
  narrowScreen.value = narrow
  collapsed.value = narrow ? true : desktopCollapsed.value
}
function toggleMenu() {
  collapsed.value = !collapsed.value
  if (!narrowScreen.value) desktopCollapsed.value = collapsed.value
}
onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
})
onBeforeUnmount(() => window.removeEventListener('resize', syncViewport))
const logoutBusy = ref(false)
const logoutError = ref('')
const avatarText = computed(() => Array.from(auth.user?.displayName?.trim() || '管理员')[0])
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  logoutError.value = ''
  const pending = auth.logout()
  await router.replace('/login')
  try {
    await pending
  } catch (cause) {
    logoutError.value = errorMessage(cause)
  } finally {
    logoutBusy.value = false
  }
}
</script>
<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <header class="admin-navbar">
      <RouterLink class="admin-brand" to="/admin/users"
        ><span class="admin-brand-name">知序</span
        ><span class="admin-brand-label">管理后台</span></RouterLink
      >
      <a-menu mode="horizontal" :selected-keys="['system']" aria-label="一级导航">
        <a-menu-item key="system">系统管理</a-menu-item>
      </a-menu>
      <div class="admin-account">
        <a-dropdown trigger="click" position="br">
          <button
            class="admin-avatar-trigger"
            data-test="account-menu-trigger"
            aria-label="账号菜单"
            aria-haspopup="menu"
            :disabled="logoutBusy"
          >
            <a-avatar :size="32">{{ avatarText }}</a-avatar>
            <span class="admin-account-name"
              ><span>{{ auth.user?.displayName }}</span
              ><span class="admin-account-role">管理员</span></span
            >
            <IconDown />
          </button>
          <template #content>
            <a-doption data-test="change-password-menu" @click="router.push('/change-password')"
              ><template #icon><IconLock /></template>修改密码</a-doption
            >
            <a-doption data-test="logout" :disabled="logoutBusy" @click="logout"
              ><template #icon><IconExport /></template>退出登录</a-doption
            >
          </template>
        </a-dropdown>
      </div>
    </header>
    <aside class="admin-sidebar" aria-label="系统管理菜单">
      <a-menu
        :collapsed="collapsed"
        :selected-keys="['users']"
        :default-open-keys="['accounts']"
        :auto-open-selected="true"
        :level-indent="34"
      >
        <a-sub-menu key="accounts"
          ><template #icon><IconUserGroup /></template><template #title>用户管理</template>
          <a-menu-item key="users" @click="router.push('/admin/users')">用户列表</a-menu-item>
        </a-sub-menu>
      </a-menu>
      <a-button
        class="admin-collapse"
        data-test="collapse-menu"
        :aria-expanded="!collapsed"
        :aria-label="collapsed ? '展开菜单' : '收起菜单'"
        :title="collapsed ? '展开菜单' : '收起菜单'"
        @click="toggleMenu"
      >
        <template #icon><IconMenuUnfold v-if="collapsed" /><IconMenuFold v-else /></template>
      </a-button>
    </aside>
    <div class="admin-content-shell">
      <main id="main" class="admin-content">
        <a-alert v-if="logoutError" type="warning">{{ logoutError }}</a-alert>
        <a-breadcrumb class="admin-breadcrumb"
          ><a-breadcrumb-item>系统管理</a-breadcrumb-item
          ><a-breadcrumb-item>用户管理</a-breadcrumb-item
          ><a-breadcrumb-item>用户列表</a-breadcrumb-item></a-breadcrumb
        >
        <RouterView />
      </main>
      <footer class="admin-footer">
        <span><span class="brand-wordmark">知序</span> · 让学习有迹可循</span>
      </footer>
    </div>
  </div>
</template>
