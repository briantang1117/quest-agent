<script setup lang="ts">
import { useConnectionStore } from '@quest/shared/connection'
const connection = useConnectionStore()
</script>
<template>
  <div class="mobile-app">
    <header class="mobile-header">
      <RouterLink class="brand" to="/sessions"
        ><span class="brand-mark">知</span
        ><span>知序<span class="brand-sub">学习助手</span></span></RouterLink
      ><RouterLink class="connection-link" to="/login"
        ><span class="status-dot" :class="{ ready: connection.health.includes('服务可用') }"></span
        >{{ connection.configured ? '连接设置' : '服务未配置' }}</RouterLink
      >
    </header>
    <RouterView />
    <nav class="bottom-nav" aria-label="移动端导航">
      <RouterLink to="/sessions"><span aria-hidden="true">聊</span>学习会话</RouterLink
      ><RouterLink to="/login"><span aria-hidden="true">连</span>连接与登录</RouterLink>
    </nav>
  </div>
</template>
