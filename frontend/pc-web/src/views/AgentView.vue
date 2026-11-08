<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createCoachApi,
  notifyError,
  subjectLabel,
  useAuthStore,
  type CoachSession,
  type CoachSessionSummary,
  type CoachTurn,
} from '@quest/shared'
import SafeMarkdown from '../components/SafeMarkdown.vue'
import CoachQuestionCard from '../components/CoachQuestionCard.vue'
const api = createCoachApi(useAuthStore()),
  route = useRoute(),
  router = useRouter()
const session = ref<CoachSession | null>(null),
  sessions = ref<CoachSessionSummary[]>([]),
  draft = ref(''),
  selected = ref<string[]>([]),
  busy = ref(false),
  loading = ref(false),
  error = ref(false)
const messages = ref<HTMLElement>()
let alive = true,
  revision = 0
onBeforeUnmount(() => {
  alive = false
  revision++
})
const current = computed(() => session.value?.currentQuestion)
// 只渲染后端可信的建议；前端不再自行拼产品文案。
const quickActions = computed(() =>
  (session.value?.messages.at(-1)?.suggestions ?? []).filter(
    (item) => item.action === 'next' || item.action === 'chat',
  ),
)
function runQuick(item: { action: 'next' | 'chat'; text?: string }) {
  if (item.action === 'next') return void send('next')
  // chat 建议带上预置话术，用户确认后再发送，不替用户直接发。
  draft.value = item.text || ''
}
// 判分结果只认题上的 result：有结果就是展示态，没有才是可作答的交互态。
function resultOf(id: string, value = session.value) {
  return value?.messages.find((item) => item.question?.id === id)?.result ?? null
}
// 当前题已作答时不再预填选项，避免展示态和待选区同时存在。
function selectionFor(value: CoachSession | null) {
  const question = value?.currentQuestion
  if (!question || resultOf(question.id, value)) return []
  return value?.suggestedKeys ?? []
}
function cardSelection(id: string) {
  if (id !== current.value?.id) return resultOf(id)?.selectedKeys ?? []
  return selected.value
}
function choose(id: string, keys: string[]) {
  if (id === current.value?.id && !resultOf(id)) selected.value = keys
}
async function list() {
  try {
    const value = await api.list()
    if (alive) sessions.value = value.list
  } catch (e) {
    notifyError(e)
  }
}
async function load() {
  const id = route.query.session
  const currentRevision = ++revision
  session.value = null
  selected.value = []
  error.value = false
  if (typeof id !== 'string') {
    await list()
    return
  }
  loading.value = true
  try {
    const value = await api.get(id)
    if (alive && currentRevision === revision) {
      session.value = value
      selected.value = selectionFor(value)
      await scroll()
    }
  } catch (e) {
    if (alive && currentRevision === revision) {
      error.value = true
      notifyError(e)
    }
  } finally {
    if (alive && currentRevision === revision) loading.value = false
  }
  await list()
}
async function scroll() {
  await nextTick()
  messages.value?.scrollTo({ top: messages.value.scrollHeight, behavior: 'smooth' })
}
async function send(action: 'chat' | 'next' | 'answer') {
  if (
    busy.value ||
    !session.value ||
    session.value.processing ||
    (action === 'chat' && !draft.value.trim()) ||
    (action === 'answer' && !selected.value.length)
  )
    return
  const currentRevision = revision,
    id = session.value.id
  const payload: CoachTurn = {
    requestId: crypto.randomUUID(),
    action,
    text: action === 'chat' ? draft.value.trim() : '',
  }
  if (action === 'answer') {
    payload.questionId = current.value?.id
    payload.keys = [...selected.value]
  }
  busy.value = true
  try {
    const result = await api.turn(id, payload)
    if (alive && currentRevision === revision) {
      session.value = result
      draft.value = ''
      selected.value = selectionFor(result)
      await scroll()
      await list()
    }
  } catch (e) {
    if (alive && currentRevision === revision) notifyError(e)
  } finally {
    if (alive && currentRevision === revision) busy.value = false
  }
}
async function newChat() {
  if (busy.value) return
  busy.value = true
  try {
    const value = await api.create(crypto.randomUUID(), 'chat')
    await router.push({ path: '/practice/agent', query: { session: value.id } })
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}
watch(
  () => (route.name === 'AgentView' ? route.query.session : undefined),
  () => {
    if (route.name === 'AgentView') void load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="personal-agent">
    <aside class="coach-sessions">
      <div class="coach-sessions-head">
        <h2>个人 Agent</h2>
        <a-button size="small" :disabled="busy" @click="newChat">新对话</a-button>
      </div>
      <RouterLink class="coach-subject-link" to="/practice/profile">{{
        session ? subjectLabel(session.subject) : '设置学习科目'
      }}</RouterLink>
      <nav aria-label="我的对话">
        <RouterLink
          v-for="item in sessions"
          :key="item.id"
          :to="{ path: '/practice/agent', query: { session: item.id } }"
          :class="{ 'is-current': session?.id === item.id }"
          ><span>{{ item.title }}</span
          ><small>{{ subjectLabel(item.subject) }}</small></RouterLink
        >
      </nav>
      <RouterLink class="text-link" to="/practice">学习空间</RouterLink>
    </aside>
    <div class="coach-conversation">
      <header class="coach-conversation-head">
        <div>
          <h1>{{ session?.title || '你的学习对话' }}</h1>
          <span>{{ session ? subjectLabel(session.subject) : '答疑与练习，在同一段对话中' }}</span>
        </div>
        <a-tag v-if="session" color="cyan">已发布题库</a-tag>
      </header>
      <div class="coach-loading" :aria-busy="loading">
        <div v-if="loading" class="coach-loading-veil" role="status">正在加载对话…</div>
        <div ref="messages" class="coach-messages" aria-live="polite">
          <a-empty v-if="error" description="对话加载失败，请重新选择会话" />
          <div v-else-if="!session" class="coach-welcome">
            <span>序</span>
            <h2>说说你想学什么。</h2>
            <p>可以从学习空间开始刷题，也可以新建对话，聊聊一个困惑的知识点。</p>
            <a-button type="primary" :disabled="busy" @click="newChat">开始对话</a-button>
          </div>
          <article
            v-for="message in session?.messages || []"
            :key="message.id"
            class="coach-message"
            :class="message.role"
          >
            <span class="message-speaker">{{ message.role === 'assistant' ? '知序' : '我' }}</span>
            <SafeMarkdown class="message-prose" :source="message.text" />
            <!-- 题目卡片内联在出题消息里，追问产生的回答不会把它挤到底部。 -->
            <CoachQuestionCard
              v-if="message.question"
              :question="message.question"
              :result="message.result"
              :interactive="message.question.id === current?.id && !message.result"
              :disabled="busy || !!session?.processing"
              :selected="cardSelection(message.question.id)"
              @update:selected="choose(message.question!.id, $event)"
              @submit="send('answer')"
            />
          </article>
          <!-- 快捷建议由后端给出（文案可来自模型，动作由 Java 权威判定），前端只循环渲染。 -->
          <div
            v-if="!busy && quickActions.length && session"
            class="coach-quick-actions"
            role="group"
            aria-label="快捷建议"
          >
            <a-button
              v-for="item in quickActions"
              :key="`${item.action}-${item.label}`"
              :data-test="`quick-${item.action}-${item.label}`"
              :type="item.action === 'next' ? 'primary' : 'secondary'"
              :disabled="busy || session.processing"
              @click="runQuick(item)"
              >{{ item.label }}</a-button
            >
          </div>
          <p v-if="busy" class="coach-thinking" role="status">知序正在思考…</p>
          <p v-if="session?.processing && !busy" class="coach-thinking">
            上一条消息仍在处理中，请稍后重新打开会话查看结果，不要重复发送。
          </p>
        </div>
      </div>
      <form v-if="session" class="coach-composer" @submit.prevent="send('chat')">
        <label class="sr-only" for="coach-input">消息</label
        ><a-textarea
          id="coach-input"
          v-model="draft"
          placeholder="输入答案、说说思路，或追问一个知识点…"
          :max-length="2000"
          :auto-size="{ minRows: 2, maxRows: 5 }"
          :disabled="busy || session.processing"
        />
        <div>
          <span>选项提交后核对答案；含糊表达会先向你确认。</span
          ><a-button
            html-type="submit"
            type="primary"
            :loading="busy"
            :disabled="!draft.trim() || session.processing"
            >发送</a-button
          >
        </div>
      </form>
    </div>
  </section>
</template>
<style scoped>
.personal-agent {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  /* 显式让行可收缩，否则grid行按内容高度撑开，矮窗口仍会页面滚动。 */
  grid-template-rows: minmax(0, 1fr);
  border: 1px solid var(--color-border-2);
  background: white;
  border-radius: 2px;
  /* 高度完全由整屏flex链决定，不再用视口常量估算。 */
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.coach-sessions {
  padding: 20px;
  border-right: 1px solid var(--color-border-2);
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 0;
}
.coach-sessions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.coach-sessions h2 {
  font-size: 17px;
  margin: 0;
}
.coach-subject-link {
  font-size: 12px;
  color: var(--color-text-3);
}
.coach-sessions nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}
.coach-sessions nav a {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px;
  border-radius: 2px;
  background: var(--color-fill-1);
}
.coach-sessions nav a.is-current {
  background: rgb(var(--primary-1));
  color: rgb(var(--primary-6));
}
.coach-sessions small {
  color: var(--color-text-3);
}
.coach-conversation {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.coach-conversation-head {
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border-2);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.coach-conversation-head h1 {
  font-size: 20px;
  margin: 0 0 4px;
  letter-spacing: 0;
}
.coach-conversation-head span {
  font-size: 12px;
  color: var(--color-text-3);
}
/* 必须是flex列：只作为普通块时子元素flex:1无效，消息区会被内容撑高。 */
.coach-loading {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 只做轻量提示遮罩，不再用组件包装层打断flex链。 */
.coach-loading {
  position: relative;
}
.coach-loading-veil {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-text-3);
  font-size: 13px;
}
.coach-messages {
  padding: 24px;
  /* 只有消息区内部滚动，页面本身保持一屏。 */
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.coach-message {
  margin-bottom: 24px;
  max-width: 76ch;
}
.coach-message.user {
  margin-left: auto;
  background: var(--color-fill-1);
  padding: 12px 16px;
  border-radius: 2px;
}
.message-speaker {
  font-size: 12px;
  color: var(--color-text-3);
}
.message-prose {
  line-height: 1.8;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
/* Markdown 渲染出的块级元素自带上下 margin，气泡里只保留段间距，末尾不留白。 */
.coach-message :deep(.message-prose > :first-child) {
  margin-top: 0;
}
.coach-message :deep(.message-prose > :last-child) {
  margin-bottom: 0;
}
.coach-message :deep(.message-prose > p) {
  margin: 0;
}
.coach-message :deep(.message-prose > p + p) {
  margin-top: 8px;
}
.coach-composer {
  padding: 16px 24px;
  border-top: 1px solid var(--color-border-2);
}
.coach-composer > div {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
}
.coach-composer span {
  font-size: 12px;
  color: var(--color-text-3);
}
.coach-welcome {
  text-align: center;
  padding: 48px 20px;
  color: var(--color-text-3);
}
.coach-welcome > span {
  font-family: var(--font-serif);
  font-size: 36px;
  color: #087d84;
}
.coach-welcome h2 {
  margin-top: 20px;
}
.coach-thinking {
  color: var(--color-text-3);
  font-size: 13px;
  margin-top: 16px;
}
/* 快捷操作像聊天建议一样贴在最后一条消息下方。 */
.coach-quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}
@media (max-width: 800px) {
  .personal-agent {
    grid-template-columns: 1fr;
  }
  .coach-sessions {
    border-right: 0;
    border-bottom: 1px solid var(--color-border-2);
  }
  .coach-sessions nav {
    max-height: 120px;
    overflow: auto;
  }
  /* 窄屏恢复自然文档流，整屏约束交给页面滚动，避免双层滚动抢手势。 */
  .personal-agent {
    flex: none;
    overflow: visible;
  }
  .coach-messages {
    flex: none;
    overflow: visible;
  }
}
</style>
