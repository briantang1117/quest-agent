<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import '@arco-design/web-vue/es/message/style/index.js'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, safeNext, useAuthStore } from '@quest/shared'
const requiredUsername = { required: true, message: '请输入用户名' }
const requiredPassword = { required: true, message: '请输入密码' }
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const busy = ref(false)
let feedback: ReturnType<typeof Message.error> | undefined
onBeforeUnmount(() => feedback?.close())
async function submit() {
  if (busy.value) return
  busy.value = true
  feedback?.close()
  try {
    await auth.login({ ...form }, { silent: true })
    form.password = ''
    if (auth.user) await router.replace(safeNext(auth.user, route.query.next))
  } catch (cause) {
    const content =
      cause instanceof ApiError && (cause.code === 40100 || cause.status === 401)
        ? '用户名或密码不正确'
        : cause instanceof ApiError && (cause.code === 42900 || cause.status === 429)
          ? '登录尝试过于频繁，请稍后再试'
          : '登录服务暂时不可用，请稍后重试'
    feedback = Message.error({ content, position: 'top', duration: 4000 })
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main id="main" class="auth-page login-page">
    <section class="auth-context">
      <p class="login-intro">让学习有迹可循</p>
      <h1>回到自己的<br />学习节奏。</h1>
      <p>整理所学，循序向前。</p>
    </section>
    <section class="auth-panel" aria-labelledby="login-title">
      <h2 id="login-title">登录<span class="brand-wordmark">知序</span></h2>
      <p>输入账号，继续今天的学习。</p>
      <a-alert v-if="!auth.configured" type="warning">{{ auth.configError }}</a-alert>
      <a-form :model="form" layout="vertical" :disabled="busy" @submit-success="submit">
        <a-form-item field="username" label="用户名" :rules="requiredUsername"
          ><a-input
            v-model="form.username"
            :input-attrs="{
              id: 'username',
              autocomplete: 'username',
              autocapitalize: 'none',
              spellcheck: false,
            }"
            placeholder="输入用户名"
        /></a-form-item>
        <a-form-item field="password" label="密码" :rules="requiredPassword"
          ><a-input-password
            v-model="form.password"
            :input-attrs="{ id: 'password', autocomplete: 'current-password' }"
            placeholder="输入密码"
        /></a-form-item>
        <a-button
          long
          type="primary"
          html-type="submit"
          :loading="busy"
          :disabled="!auth.configured"
          >登录</a-button
        >
      </a-form>
      <p class="auth-note">忘记密码？请联系管理员。</p>
    </section>
  </main>
</template>
