<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, homePath, PASSWORD_HINT, useAuthStore } from '@quest/shared'
import { passwordRule, validationRule } from '../form-rules'
const auth = useAuthStore()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const busy = ref(false)
const error = ref('')
const confirmRule = validationRule((value) =>
  value === form.newPassword ? '' : '两次输入的新密码不一致',
)
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
    })
    form.currentPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    if (auth.user) await router.replace(homePath(auth.user))
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main id="main" class="auth-page password-page">
    <section class="auth-context">
      <h1>{{ auth.user?.mustChangePassword ? '先设置属于你的密码。' : '更新登录密码。' }}</h1>
      <p v-if="auth.user?.mustChangePassword">当前使用初始或重置密码。完成改密后才能进入工作区。</p>
      <p>改密会撤销旧会话，并为当前页面建立新会话。请勿与他人共享密码。</p>
    </section>
    <section class="auth-panel" aria-labelledby="password-title">
      <h2 id="password-title">修改密码</h2>
      <p>{{ auth.user?.username }}</p>
      <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
      <a-form :model="form" layout="vertical" :disabled="busy" @submit-success="submit">
        <a-form-item field="currentPassword" label="当前密码" :rules="passwordRule"
          ><a-input-password
            v-model="form.currentPassword"
            :input-attrs="{ id: 'current-password', autocomplete: 'current-password' }"
        /></a-form-item>
        <a-form-item field="newPassword" label="新密码" :rules="passwordRule" :extra="PASSWORD_HINT"
          ><a-input-password
            v-model="form.newPassword"
            :input-attrs="{ id: 'new-password', autocomplete: 'new-password' }"
        /></a-form-item>
        <a-form-item field="confirmPassword" label="确认新密码" :rules="confirmRule"
          ><a-input-password
            v-model="form.confirmPassword"
            :input-attrs="{ id: 'confirm-password', autocomplete: 'new-password' }"
        /></a-form-item>
        <a-button long type="primary" html-type="submit" :loading="busy">修改密码并继续</a-button>
      </a-form>
    </section>
  </main>
</template>
