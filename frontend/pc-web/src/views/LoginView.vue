<script setup lang="ts">
import { computed } from 'vue'
import { useConnectionStore } from '@quest/shared/connection'
const connection = useConnectionStore()
const token = computed({ get: () => connection.token, set: value => connection.setToken(value) })
</script>
<template>
  <main id="main" class="page-container connection-page"><section class="page-heading"><div><p class="breadcrumb"><RouterLink to="/practice">刷题空间</RouterLink> / 连接与登录</p><h1>连接真实服务。</h1><p class="lead">输入已有登录 Token，用于后续联调。当前不提供账号登录、注册或签发凭据。</p></div></section>
    <a-alert v-if="!connection.configured" type="warning">{{ connection.configError }}。请在当前应用 .env.local 配置后重启开发服务。</a-alert>
    <div class="connection-grid"><section class="panel"><h2>Java 服务连接</h2><p>所有业务请求都经过 Java，不直接连接 AI 内部服务。</p><dl class="connection-details"><dt>服务根地址</dt><dd>{{ connection.baseURL || '未配置' }}</dd><dt>健康检查</dt><dd>/actuator/health（公开）</dd><dt>当前状态</dt><dd role="status">{{ connection.health }}</dd></dl><a-button :disabled="!connection.configured" :loading="connection.healthBusy" @click="connection.checkHealth()">检查服务连接</a-button><p class="fine-print">仅手动触发，不轮询、不自动重试。</p></section>
    <section class="panel"><h2>登录凭据</h2><p>粘贴已有 Bearer Token。仅保留于页面内存，刷新即清除。</p><label class="field-label" for="login-token">访问 Token</label><a-input-password v-model="token" placeholder="输入 Token 或 Bearer …" :input-attrs="{ id: 'login-token', autocomplete: 'off' }"/><div class="button-row"><a-button type="primary" :disabled="!connection.configured || !connection.token" :loading="connection.systemBusy" @click="connection.checkSystem()">验证凭据并读取系统信息</a-button><a-button :disabled="!connection.token" @click="connection.clearToken()">清除凭据</a-button></div><p role="status" class="auth-status">{{ connection.systemStatus }}</p><p class="fine-print">请求 /api/v1/system/info；成功不等于获得管理角色。角色与路由权限尚未接入。</p></section></div>
    <section v-if="connection.systemInfo !== null" class="panel system-result"><h2>服务返回的系统信息</h2><pre>{{ JSON.stringify(connection.systemInfo, null, 2) }}</pre></section>
    <section class="config-help"><h2>本地配置</h2><p>复制当前应用的 .env.example 为 .env.local。开发时可设置 VITE_API_BASE_URL=/gateway 与 DEV_API_PROXY_TARGET=http://localhost:8080。也可直接填写 Java 服务根地址，但服务需允许当前来源的 CORS。</p><p>生产构建中的 VITE_* 配置公开可见，不要填写 Token、模型密钥或内部服务凭据。</p></section>
  </main>
</template>
