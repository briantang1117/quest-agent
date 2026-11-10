<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import {
  createCoachApi,
  notifyError,
  STUDY_SUBJECTS,
  useAuthStore,
  type StudySubject,
} from '@quest/shared'
const auth = useAuthStore(),
  api = createCoachApi(auth)
const subject = ref<StudySubject | undefined>(undefined),
  loading = ref(false),
  saving = ref(false)
const examYear = ref('')
const yearError = ref('')
let alive = true
onBeforeUnmount(() => {
  alive = false
})
onMounted(async () => {
  loading.value = true
  try {
    const value = await api.preference()
    if (alive) {
      subject.value = value.subject ?? undefined
      examYear.value = value.examYear === null ? '' : String(value.examYear)
    }
  } catch (e) {
    notifyError(e)
  } finally {
    if (alive) loading.value = false
  }
})
async function save() {
  if (!subject.value || saving.value) return
  const year = examYear.value.trim() === '' ? null : Number(examYear.value)
  yearError.value =
    year !== null &&
    (!/^\d{4}$/.test(examYear.value.trim()) ||
      !Number.isInteger(year) ||
      year < 2000 ||
      year > 2100)
      ? '考试年份须为2000至2100的整数，或留空'
      : ''
  if (yearError.value) return
  saving.value = true
  try {
    await api.setPreference(subject.value, year)
    if (alive) Message.success('学习设置已保存，新对话将使用这些设置')
  } catch (e) {
    notifyError(e)
  } finally {
    if (alive) saving.value = false
  }
}
</script>
<template>
  <section class="page-heading">
    <div>
      <p class="breadcrumb">个人中心</p>
      <h1>把学习方向定下来。</h1>
      <p class="lead">{{ auth.user?.displayName }}，你的学习空间与个人 Agent 共用这里的设置。</p>
    </div>
  </section>
  <a-card title="当前学习科目" :bordered="false" class="profile-card"
    ><p>一次专注一个科目。更改后，新对话自动使用新的科目，已有对话保持原来的方向。</p>
    <a-spin :loading="loading"
      ><a-radio-group v-model="subject" direction="vertical" :disabled="loading || saving"
        ><a-radio v-for="item in STUDY_SUBJECTS" :key="item.value" :value="item.value">{{
          item.label
        }}</a-radio></a-radio-group
      ></a-spin
    >
    <a-form :model="{ examYear }" layout="vertical">
      <a-form-item
        label="目标考试年份"
        :validate-status="yearError ? 'error' : undefined"
        :help="yearError || undefined"
        extra="可留空；仅新对话使用所选年份，不自动改用其他年份考纲。"
      >
        <a-input
          v-model="examYear"
          data-test="exam-year"
          allow-clear
          placeholder="2000–2100（可选）"
          :disabled="loading || saving"
          :input-attrs="{ inputmode: 'numeric', 'aria-label': '目标考试年份' }"
        />
      </a-form-item>
    </a-form>
    <div class="profile-actions">
      <a-button
        data-test="save-subject"
        type="primary"
        :loading="saving"
        :disabled="!subject || loading"
        @click="save"
        >保存科目</a-button
      ><RouterLink class="text-link" to="/practice">返回学习空间</RouterLink>
    </div></a-card
  >
  <a-card title="账号" :bordered="false" class="profile-card"
    ><p>{{ auth.user?.displayName }} · {{ auth.user?.username }}</p>
    <RouterLink class="text-link" to="/change-password">修改密码</RouterLink></a-card
  >
</template>
<style scoped>
.profile-card {
  max-width: 800px;
  margin-bottom: 20px;
  border-radius: 2px;
}
.profile-card p {
  color: var(--color-text-3);
}
.profile-actions {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: 24px;
}
</style>
