<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage, safeNext, useAuthStore } from '@quest/shared'
const requiredUsername = { required: true, message: '请输入用户名' }
const requiredPassword = { required: true, message: '请输入密码' }
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.login({ ...form })
    form.password = ''
    if (auth.user) await router.replace(safeNext(auth.user, route.query.next))
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main id="main" class="auth-page">
    <section class="auth-context">
      <span class="brand-mark" aria-hidden="true">序</span>
      <h1>回到自己的<br />学习节奏。</h1>
      <p>知序为每个人保留独立的学习工作区。</p>
    </section>
    <section class="auth-panel" aria-labelledby="login-title">
      <h2 id="login-title">登录知序</h2>
      <p>输入账号，继续今天的学习。</p>
      <a-alert v-if="!auth.configured" type="warning">{{ auth.configError }}</a-alert>
      <a-alert v-if="auth.sessionNotice" type="warning">{{ auth.sessionNotice }}</a-alert>
      <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
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
