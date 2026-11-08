<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { IconArrowRight, IconMessage, IconUser } from '@arco-design/web-vue/es/icon'
import {
  createCoachApi,
  notifyError,
  subjectLabel,
  useAuthStore,
  type StudySubject,
  type CoachSessionSummary,
} from '@quest/shared'
const api = createCoachApi(useAuthStore()),
  router = useRouter()
const subject = ref<StudySubject | null>(null),
  sessions = ref<CoachSessionSummary[]>([]),
  busy = ref(false),
  loaded = ref(false)
let pending = false
async function load() {
  if (pending) return
  pending = true
  try {
    const [preference, list] = await Promise.all([api.preference(), api.list()])
    subject.value = preference.subject
    sessions.value = list.list
    loaded.value = true
  } catch (e) {
    notifyError(e)
  } finally {
    pending = false
  }
}
onMounted(load)
onActivated(load)
async function start(mode: 'practice' | 'chat') {
  if (busy.value || !subject.value) return
  busy.value = true
  try {
    const session = await api.create(crypto.randomUUID(), mode)
    await router.push({ path: '/practice/agent', query: { session: session.id } })
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="page-heading">
    <div>
      <p class="breadcrumb">学习 / 刷题空间</p>
      <h1>从一道题，聊懂一个知识点。</h1>
      <p class="lead">不必选试卷。个人 Agent 会从当前科目的已发布题目中，陪你一道一道练。</p>
    </div>
    <RouterLink to="/practice/profile" class="subject-entry"
      ><IconUser />{{ subjectLabel(subject) }}</RouterLink
    >
  </section>
  <a-alert v-if="loaded && !subject" type="info"
    >先到<RouterLink to="/practice/profile">个人中心选择学习科目</RouterLink
    >，再开始你的第一段练习。</a-alert
  >
  <div class="coach-entry-grid">
    <article class="coach-entry primary">
      <span class="entry-symbol" aria-hidden="true">练</span>
      <h2>开始互动刷题</h2>
      <p>Agent 出一道题，你来选择或说思路。答完核对，再聊聊为什么。</p>
      <a-button
        data-test="start-practice"
        type="primary"
        :loading="busy"
        :disabled="!subject"
        @click="start('practice')"
        >开始刷题<template #icon><IconArrowRight /></template
      ></a-button>
    </article>
    <article class="coach-entry">
      <IconMessage class="entry-symbol" />
      <h2>和个人 Agent 聊聊</h2>
      <p>从一个不懂的知识点开始，也可以在对话中随时转入练习。</p>
      <a-button :disabled="!subject || busy" @click="start('chat')">进入对话</a-button>
    </article>
  </div>
  <section v-if="sessions.length" class="recent-coach">
    <h2>继续上次对话</h2>
    <a-list :bordered="false"
      ><a-list-item v-for="session in sessions.slice(0, 5)" :key="session.id"
        ><RouterLink :to="{ path: '/practice/agent', query: { session: session.id } }"
          ><strong>{{ session.title }}</strong
          ><span>{{ subjectLabel(session.subject) }}</span
          ><IconArrowRight /></RouterLink></a-list-item
    ></a-list>
  </section>
</template>
<style scoped>
.subject-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  color: var(--color-text-2);
  padding: 10px 14px;
  background: white;
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
}
.coach-entry-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  align-items: stretch;
  gap: 24px;
  margin-top: 28px;
}
.coach-entry {
  display: flex;
  flex-direction: column;
  padding: 32px;
  background: white;
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
}
.coach-entry.primary {
  border-top: 3px solid #087d84;
}
.entry-symbol {
  font-size: 30px;
  color: #087d84;
  font-family: var(--font-serif);
}
.coach-entry h2 {
  margin-top: 20px;
}
.coach-entry p {
  color: var(--color-text-3);
}
/* 两卡等高由grid拉伸决定，按钮靠底对齐，不再用固定min-height硬撑留白。 */
.coach-entry .arco-btn {
  align-self: flex-start;
  margin-top: auto;
}
.recent-coach {
  margin-top: 32px;
}
.recent-coach a {
  display: flex;
  align-items: center;
  gap: 20px;
  width: 100%;
}
.recent-coach a span {
  color: var(--color-text-3);
  margin-left: auto;
}
.recent-coach strong {
  font-weight: 500;
}
@media (max-width: 800px) {
  .coach-entry-grid {
    grid-template-columns: 1fr;
  }
  .page-heading {
    flex-direction: column;
  }
}
</style>
