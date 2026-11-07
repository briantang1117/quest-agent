<script setup lang="ts">
import { computed } from 'vue'
import { useConnectionStore } from '@quest/shared/connection'
const connection = useConnectionStore()
const token = computed({ get: () => connection.token, set: value => connection.setToken(value) })
</script>
<template>
  <main class="mobile-main login-main"><h1>连接与登录</h1><p class="page-description">使用已有 Token 联调真实 Java 服务。账号登录与注册尚未接入。</p>
    <van-notice-bar v-if="!connection.configured" wrapable :scrollable="false" :text="connection.configError" />
    <section class="mobile-panel"><h2>服务连接</h2><dl class="connection-details"><dt>服务根地址</dt><dd>{{ connection.baseURL || '未配置' }}</dd><dt>健康状态</dt><dd role="status">{{ connection.health }}</dd></dl><van-button block plain type="primary" :disabled="!connection.configured" :loading="connection.healthBusy" @click="connection.checkHealth()">检查服务连接</van-button><p class="hint">仅检查公开的 /actuator/health，不轮询或自动重试。</p></section>
    <section class="mobile-panel"><h2>登录凭据</h2><p class="hint">仅保存在页面内存，刷新即清除。不要在共享设备上留下凭据。</p><van-field v-model="token" label="Token" type="password" placeholder="输入已有访问 Token" autocomplete="off" :border="false" class="token-field"/><van-button block type="primary" :disabled="!connection.configured || !connection.token" :loading="connection.systemBusy" @click="connection.checkSystem()">验证凭据并读取系统信息</van-button><button class="clear-token" :disabled="!connection.token" @click="connection.clearToken()">清除凭据</button><p class="auth-status" role="status">{{ connection.systemStatus }}</p><p class="hint">使用受保护的 /api/v1/system/info。当前未接入用户身份与角色接口。</p></section>
    <section v-if="connection.systemInfo !== null" class="mobile-panel"><h2>系统信息</h2><pre class="system-info">{{ JSON.stringify(connection.systemInfo, null, 2) }}</pre></section>
    <section class="mobile-config"><h2>本地配置提示</h2><p>在 mobile-agent/.env.local 设置 VITE_API_BASE_URL，填写 Java 服务根地址，不要附加 /api/v1。</p><p>开发代理可设置 VITE_API_BASE_URL=/gateway 与 DEV_API_PROXY_TARGET=http://localhost:8080，保存后重启。VITE_* 变量公开可见，不能存放密钥。</p></section>
  </main>
</template>
