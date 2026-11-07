<script setup lang="ts">
import { useConnectionStore } from '@quest/shared/connection'
const connection = useConnectionStore()
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="topbar">
    <RouterLink class="brand" to="/practice"><span class="brand-mark">知</span><span>知序<span class="brand-sub">刷题与题库工作台</span></span></RouterLink>
    <nav aria-label="工作空间"><RouterLink to="/practice">刷题空间</RouterLink><RouterLink to="/admin">管理工作台</RouterLink></nav>
    <RouterLink class="connection-link" to="/login"><span class="status-dot" :class="{ ready: connection.health.includes('服务可用') }"></span>{{ connection.configured ? connection.health : '服务未配置' }}<span class="connection-divider"> / </span>{{ connection.token ? '已输入凭据' : '连接与登录' }}</RouterLink>
  </header>
  <div class="foundation-banner"><span>工程预览</span> 当前仅提供页面框架与联调能力。题库、练习及 Agent 业务尚未接入。</div>
  <RouterView />
  <footer class="site-footer"><span>知序 · 让学习有迹可循</span><span>第一期工程底座，不展示模拟业务数据</span></footer>
</template>
