<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import '@arco-design/web-vue/es/message/style/index.js'
import { useRouter } from 'vue-router'
import { ApiError, homePath, PASSWORD_HINT, useAuthStore } from '@quest/shared'
import { passwordRule, validationRule } from '../form-rules'
const auth = useAuthStore()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const busy = ref(false)
const currentPasswordRule = { required: true, message: '请输入当前密码' }
const confirmRule = validationRule((value) =>
  value === form.newPassword ? '' : '两次输入的新密码不一致',
)
async function submit() {
  if (busy.value) return
  busy.value = true
  try {
    await auth.changePassword(
      { currentPassword: form.currentPassword, newPassword: form.newPassword },
      { silent: true },
    )
    form.currentPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    Message.success({ content: '密码修改成功', duration: 3000 })
    if (auth.user) await router.replace(homePath(auth.user))
  } catch (cause) {
    const content =
      cause instanceof ApiError && (cause.status === 401 || cause.code === 40100)
        ? '当前密码不正确或登录已失效，请重新确认'
        : cause instanceof ApiError && cause.code === 40000
          ? '请检查新密码要求，新密码不能与当前密码相同'
          : '修改密码失败，请稍后重试'
    Message.error({ content, position: 'top', duration: 4000 })
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main id="main" class="auth-page login-page password-page">
    <section class="auth-context">
      <p class="login-intro">让学习有迹可循</p>
      <h1>{{ auth.user?.mustChangePassword ? '设置自己的密码。' : '安心继续，循序向前。' }}</h1>
      <p>
        {{
          auth.user?.mustChangePassword
            ? '完成密码设置，开始今天的学习。'
            : '更新密码，守护自己的学习空间。'
        }}
      </p>
    </section>
    <section class="auth-panel" aria-labelledby="password-title">
      <h2 id="password-title">修改密码</h2>
      <p>为<span class="brand-wordmark">知序</span>账号 {{ auth.user?.username }} 设置新密码。</p>
      <a-form :model="form" layout="vertical" :disabled="busy" @submit-success="submit">
        <a-form-item field="currentPassword" label="当前密码" :rules="currentPasswordRule"
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
