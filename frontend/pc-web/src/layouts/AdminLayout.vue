<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  IconUserGroup,
  IconBook,
  IconFile,
  IconDown,
  IconLock,
  IconExport,
  IconMenuFold,
  IconMenuUnfold,
} from '@arco-design/web-vue/es/icon'
import { notifyError, useAuthStore } from '@quest/shared'
import './admin-layout.css'
import DepthRouterView from '../components/DepthRouterView.vue'
import AdminBreadcrumb from '../components/AdminBreadcrumb.vue'
import { useCacheScope } from '../composables/cache-scope'
const cacheScope = useCacheScope()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const syllabusSection = computed(
  () => route.path === '/admin/syllabi' || route.path.startsWith('/admin/syllabi/'),
)
const contentSection = computed(
  () =>
    route.path.startsWith('/admin/banks') ||
    route.path.startsWith('/admin/questions/') ||
    syllabusSection.value,
)
// 系统日志挂在"系统管理"下，与用户管理同级，但不复用用户管理的选中态。
const systemSection = computed(() =>
  ['/admin/logs', '/admin/events', '/admin/statistics'].some((path) => route.path.startsWith(path)),
)
const breadcrumbs = computed(() => {
  if (syllabusSection.value) {
    const items: { label: string; to?: string }[] = [
      { label: '内容管理' },
      { label: '考纲管理', to: '/admin/syllabi' },
    ]
    if (route.name === 'AdminSyllabusDetailView') items.push({ label: '考纲详情' })
    return items
  }
  // 系统日志与用户管理并列，同属系统管理一级。
  if (systemSection.value)
    return [{ label: '系统管理' }, { label: String(route.meta.title || '系统日志') }]
  if (!contentSection.value)
    return [{ label: '系统管理' }, { label: '用户管理' }, { label: '用户列表' }]
  const items: { label: string; to?: string }[] = [
    { label: '内容管理' },
    { label: '题库管理' },
    { label: '公共题库', to: '/admin/banks' },
  ]
  if (route.name === 'AdminBankDetailView') items.push({ label: '管理题库' })
  else if (route.name === 'AdminQuestionView') {
    const bankId = route.query.bankId
    if (typeof bankId === 'string' && /^[0-9a-f-]{36}$/.test(bankId))
      items.push({ label: '管理题库', to: `/admin/banks/${bankId}` })
    items.push({ label: '题目审核' })
  }
  return items
})
const breadcrumbBackTo = computed(
  () =>
    breadcrumbs.value
      .slice(0, -1)
      .reverse()
      .find((item) => item.to)?.to ?? '/admin/banks',
)
const openKeys = ref<string[]>([])
watch(
  [contentSection, systemSection, syllabusSection],
  ([isContent, isSystem, isSyllabus]) => {
    openKeys.value = [
      isSyllabus ? 'syllabi' : isContent ? 'question-banks' : isSystem ? 'system-logs' : 'accounts',
    ]
  },
  { immediate: true },
)
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
const avatarText = computed(() => Array.from(auth.user?.displayName?.trim() || '管理员')[0])
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
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
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <header class="admin-navbar">
      <RouterLink class="admin-brand" to="/admin/users"
        ><span class="admin-brand-name">知序</span
        ><span class="admin-brand-label">管理后台</span></RouterLink
      >
      <a-menu
        mode="horizontal"
        :selected-keys="[contentSection ? 'content' : 'system']"
        aria-label="一级导航"
      >
        <a-menu-item key="content" @click="router.push('/admin/banks')">内容管理</a-menu-item>
        <a-menu-item key="system" @click="router.push('/admin/users')">系统管理</a-menu-item>
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
        v-model:open-keys="openKeys"
        :collapsed="collapsed"
        :selected-keys="[
          syllabusSection
            ? 'syllabus-list'
            : contentSection
              ? 'banks'
              : systemSection
                ? route.path === '/admin/statistics'
                  ? 'statistics'
                  : route.path === '/admin/events'
                    ? 'events'
                    : 'logs'
                : 'users',
        ]"
        :auto-open-selected="true"
        :level-indent="34"
      >
        <template v-if="contentSection">
          <a-sub-menu key="question-banks"
            ><template #icon><IconBook /></template><template #title>题库管理</template
            ><a-menu-item key="banks" @click="router.push('/admin/banks')"
              >公共题库</a-menu-item
            ></a-sub-menu
          >
          <a-sub-menu key="syllabi"
            ><template #icon><IconFile /></template><template #title>考纲管理</template
            ><a-menu-item key="syllabus-list" @click="router.push('/admin/syllabi')"
              >考纲列表</a-menu-item
            ></a-sub-menu
          >
        </template>
        <!-- 系统管理下并列两组：用户管理、系统日志，不互相替换。 -->
        <template v-else>
          <a-sub-menu key="accounts"
            ><template #icon><IconUserGroup /></template><template #title>用户管理</template>
            <a-menu-item key="users" @click="router.push('/admin/users')">用户列表</a-menu-item>
          </a-sub-menu>
          <a-sub-menu key="system-logs"
            ><template #icon><IconFile /></template><template #title>系统日志</template>
            <a-menu-item key="events" @click="router.push('/admin/events')"
              >登录与操作日志</a-menu-item
            >
            <a-menu-item key="statistics" @click="router.push('/admin/statistics')"
              >使用统计</a-menu-item
            >
            <a-menu-item key="logs" @click="router.push('/admin/logs')">AI 调用记录</a-menu-item>
          </a-sub-menu>
        </template>
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
        <AdminBreadcrumb :items="breadcrumbs" :back-to="breadcrumbBackTo" />
        <DepthRouterView :key="cacheScope" />
      </main>
      <footer class="admin-footer">
        <span><span class="brand-wordmark">知序</span> · 让学习有迹可循</span>
      </footer>
    </div>
  </div>
</template>
