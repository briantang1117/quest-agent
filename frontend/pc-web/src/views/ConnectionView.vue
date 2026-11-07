<script setup lang="ts">
import { computed } from 'vue'
import { useConnectionStore } from '@quest/shared/connection'
const connection = useConnectionStore()
const token = computed({ get: () => connection.token, set: (value) => connection.setToken(value) })
</script>
<template>
  <main id="main" class="page-container connection-page">
    <section class="page-heading">
      <div>
        <h1>服务连接诊断</h1>
        <p class="lead">
          独立开发诊断工具。这里输入的 Token 不会登录账号、改变角色或开放任何工作区权限。
        </p>
      </div>
    </section>
    <a-alert v-if="!connection.configured" type="warning">{{ connection.configError }}</a-alert>
    <div class="connection-grid">
      <section class="panel">
        <h2>Java 服务连接</h2>
        <p>所有业务请求都经过 Java，不直接连接 AI 内部服务。</p>
        <dl class="connection-details">
          <dt>服务根地址</dt>
          <dd>{{ connection.baseURL || '未配置' }}</dd>
          <dt>当前状态</dt>
          <dd role="status">{{ connection.health }}</dd>
        </dl>
        <a-button
          :disabled="!connection.configured"
          :loading="connection.healthBusy"
          @click="connection.checkHealth()"
          >检查服务连接</a-button
        >
        <p class="fine-print">仅手动触发，不轮询、不自动重试。</p>
      </section>
      <section class="panel">
        <h2>独立凭据验证</h2>
        <p>仅保留于诊断内存，刷新即清除。不会写入账号会话。</p>
        <label class="field-label" for="diagnostic-token">诊断 Token</label>
        <a-input-password
          v-model="token"
          placeholder="输入 Token 或 Bearer …"
          :input-attrs="{ id: 'diagnostic-token', autocomplete: 'off' }"
        />
        <div class="button-row">
          <a-button
            :disabled="!connection.configured || !connection.token"
            :loading="connection.systemBusy"
            @click="connection.checkSystem()"
            >验证系统信息</a-button
          ><a-button @click="connection.clearToken()">清除诊断凭据</a-button>
        </div>
        <p role="status" class="auth-status">{{ connection.systemStatus }}</p>
        <p class="fine-print">请求 /api/v1/system/info；成功不等于获得管理角色。</p>
      </section>
    </div>
    <section v-if="connection.systemInfo !== null" class="panel system-result">
      <h2>服务返回的系统信息</h2>
      <pre>{{ JSON.stringify(connection.systemInfo, null, 2) }}</pre>
    </section>
  </main>
</template>
