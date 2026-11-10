<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconDelete, IconMore } from '@arco-design/web-vue/es/icon'
import {
  createCoachApi,
  notifyError,
  streamTurn,
  subjectLabel,
  useAuthStore,
  type CoachSession,
  type CoachSessionSummary,
  type CoachTurn,
} from '@quest/shared'
import SafeMarkdown from '../components/SafeMarkdown.vue'
import CoachQuestionCard from '../components/CoachQuestionCard.vue'
const auth = useAuthStore(),
  api = createCoachApi(auth),
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
const deleting = ref<string | null>(null)
let listRevision = 0
// 仅在服务端 state 确认之前展示乐观消息。
const optimistic = ref<{ id: string; role: 'user'; text: string } | null>(null)
// 临时文本不写入会话；done 必须整体替换为持久化结果。
const streaming = ref('')
let alive = true,
  revision = 0
let active: { requestId: string; controller: AbortController } | null = null
function cancelPending() {
  revision++
  active?.controller.abort()
  active = null
  optimistic.value = null
  streaming.value = ''
  busy.value = false
  loading.value = false
  draft.value = ''
}
onBeforeUnmount(() => {
  // 卸载只停止本地回写：已发出的回答由后端生成并落库，
  // 主动 abort 会把它变成"本次回答未完成"，用户回来只能看到失败。
  alive = false
  revision++
  active = null
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
  if (item.text?.trim()) void send('chat', item.text)
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
async function list(expectedRevision = revision) {
  const requestRevision = ++listRevision
  try {
    const value = await api.list(true)
    if (alive && expectedRevision === revision && requestRevision === listRevision)
      sessions.value = value.list
  } catch (e) {
    if (alive && expectedRevision === revision && requestRevision === listRevision) notifyError(e)
  }
}
function cannotDelete(id: string) {
  return deleting.value !== null || (route.query.session === id && loading.value)
}
async function deleteSession(id: string) {
  if (cannotDelete(id)) return
  deleting.value = id
  try {
    await api.remove(id)
    if (!alive) return
    // 使删除前发出的列表响应失效，避免旧结果把已删除会话加回来。
    listRevision++
    const index = sessions.value.findIndex((item) => item.id === id)
    const remaining = sessions.value.filter((item) => item.id !== id)
    sessions.value = remaining
    if (route.name === 'AgentView' && route.query.session === id) {
      cancelPending()
      const next = remaining[Math.min(Math.max(index, 0), remaining.length - 1)]
      await router.replace({ path: '/practice/agent', query: next ? { session: next.id } : {} })
    } else {
      void list()
    }
  } catch (e) {
    if (alive) notifyError(e)
  } finally {
    deleting.value = null
  }
}
async function load() {
  const id = route.query.session
  cancelPending()
  const currentRevision = revision
  session.value = null
  selected.value = []
  error.value = false
  // 列表与会话详情独立加载，不等待详情或整段 AI 回答。
  void list(currentRevision)
  if (typeof id !== 'string') return
  loading.value = true
  try {
    const value = await api.get(id, true)
    if (alive && currentRevision === revision) {
      session.value = value
      selected.value = selectionFor(value)
      loading.value = false
      await scroll()
      // 消费一次章节首句并先移除query，刷新或缓存激活不重复发送。
      const prompt = route.query.prompt
      if (
        typeof prompt === 'string' &&
        prompt.trim() &&
        prompt.length <= 2000 &&
        !value.processing
      ) {
        const query = { ...route.query }
        delete query.prompt
        await router.replace({ path: route.path, query })
        if (alive && currentRevision === revision) void send('chat', prompt)
      }
    }
  } catch (e) {
    if (alive && currentRevision === revision) {
      error.value = true
      notifyError(e)
    }
  } finally {
    if (alive && currentRevision === revision) loading.value = false
  }
}
function nearBottom() {
  const element = messages.value
  return !element || element.scrollHeight - element.scrollTop - element.clientHeight < 96
}
async function scroll(expectedRevision = revision, follow = true) {
  await nextTick()
  // 部分环境（jsdom/旧浏览器）没有 scrollTo，滚动失败不应影响发送主流程。
  if (alive && expectedRevision === revision && follow)
    messages.value?.scrollTo?.({ top: messages.value.scrollHeight, behavior: 'auto' })
}
async function send(action: 'chat' | 'next' | 'answer', text = draft.value) {
  if (
    busy.value ||
    !session.value ||
    session.value.processing ||
    (action === 'chat' && !text.trim()) ||
    (action === 'answer' && !selected.value.length)
  )
    return
  const currentRevision = revision,
    id = session.value.id,
    initialMessageCount = session.value.messages.length
  const payload: CoachTurn = {
    requestId: crypto.randomUUID(),
    action,
    text: action === 'chat' ? text.trim() : '',
  }
  if (action === 'answer') {
    payload.questionId = current.value?.id
    payload.keys = [...selected.value]
  }
  const controller = new AbortController()
  active = { requestId: payload.requestId, controller }
  const isCurrent = () =>
    alive && revision === currentRevision && active?.requestId === payload.requestId
  busy.value = true
  const sent =
    action === 'chat'
      ? payload.text
      : action === 'next'
        ? '下一题'
        : `我的答案：${payload.keys!.join('、')}`
  optimistic.value = { id: `pending-${payload.requestId}`, role: 'user', text: sent }
  if (action === 'chat') draft.value = ''
  void scroll(currentRevision)
  let confirmed = false
  const accept = (value: CoachSession) => {
    if (!isCurrent()) return
    const follow = nearBottom()
    session.value = value
    optimistic.value = null
    selected.value = selectionFor(value)
    confirmed = true
    void scroll(currentRevision, follow)
  }
  try {
    await auth.streamRequest(({ token, baseURL, assertCurrent }) =>
      streamTurn(id, payload, {
        token,
        baseURL,
        signal: controller.signal,
        onState: (value) => {
          assertCurrent()
          accept(value)
        },
        onDelta: (text) => {
          assertCurrent()
          if (!isCurrent()) return
          const follow = nearBottom()
          streaming.value += text
          void scroll(currentRevision, follow)
        },
        onReset: () => {
          assertCurrent()
          if (isCurrent()) streaming.value = ''
        },
        onDone: (value) => {
          assertCurrent()
          if (!isCurrent()) return
          streaming.value = ''
          accept(value)
        },
      }),
    )
    if (isCurrent()) await list(currentRevision)
  } catch (e) {
    // 删除提交会先关闭服务端流；不要把预期中断当失败再读取已删除会话。
    if (!isCurrent() || deleting.value === id) return
    optimistic.value = null
    streaming.value = ''
    notifyError(e)
    // 断流不代表服务器回滚；只读恢复，不重发付费 POST。
    if (auth.authenticated && !controller.signal.aborted) {
      try {
        const value = await api.get(id, true)
        if (!isCurrent()) return
        if (
          value.messages
            .slice(initialMessageCount)
            .some((item) => item.role === 'user' && item.text === sent)
        )
          confirmed = true
        session.value = value
        selected.value = selectionFor(value)
      } catch (recoveryError) {
        if (isCurrent()) notifyError(recoveryError)
      }
    }
    if (isCurrent() && action === 'chat' && !confirmed) draft.value = text
  } finally {
    if (isCurrent()) {
      optimistic.value = null
      streaming.value = ''
      busy.value = false
      active = null
    }
  }
}
/**
 * 回车发送、Shift+回车换行。
 * 输入法组合输入（拼音选词）期间的 Enter 归输入法，不能误发。
 */
function onComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  // 中文输入法组词中，Enter 用于确认候选词。
  if (event.isComposing || (event as KeyboardEvent & { keyCode?: number }).keyCode === 229) return
  event.preventDefault()
  void send('chat')
}
async function newChat() {
  if (busy.value) return
  const expectedRevision = revision
  busy.value = true
  try {
    const value = await api.create(crypto.randomUUID(), 'chat')
    if (alive && revision === expectedRevision)
      await router.push({ path: '/practice/agent', query: { session: value.id } })
  } catch (e) {
    if (alive && revision === expectedRevision) notifyError(e)
  } finally {
    if (alive && revision === expectedRevision) busy.value = false
  }
}
watch(
  [() => route.name, () => route.query.session],
  () => {
    if (route.name === 'AgentView') void load()
    else cancelPending()
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
        <div
          v-for="item in sessions"
          :key="item.id"
          class="coach-session-item"
          :class="{ 'is-current': route.query.session === item.id }"
        >
          <RouterLink :to="{ path: '/practice/agent', query: { session: item.id } }">
            <span>{{ item.title }}</span>
            <small>{{ subjectLabel(item.subject) }}</small>
          </RouterLink>
          <a-dropdown trigger="click" position="br" @select="deleteSession(item.id)">
            <a-button
              class="coach-session-menu"
              type="text"
              size="small"
              :aria-label="`对话操作：${item.title}`"
              :data-test="`session-menu-${item.id}`"
              :loading="deleting === item.id"
              @click.stop
              ><template #icon><IconMore /></template
            ></a-button>
            <template #content>
              <a-doption
                value="delete"
                class="coach-session-delete"
                :data-test="`delete-session-${item.id}`"
                :disabled="cannotDelete(item.id)"
                ><template #icon><IconDelete /></template>删除</a-doption
              >
            </template>
          </a-dropdown>
        </div>
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
          <!-- 服务端确认前先显示自己的消息，所有动作都立即反馈。 -->
          <article v-if="optimistic" class="coach-message user is-pending">
            <span class="message-speaker">我</span>
            <SafeMarkdown class="message-prose" :source="optimistic.text" />
          </article>
          <!-- 临时文本仅用于流式展示，done 后由权威会话替换。 -->
          <article v-if="streaming" class="coach-message assistant is-streaming">
            <span class="message-speaker">知序</span>
            <SafeMarkdown class="message-prose" :source="streaming" />
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
            上一条消息仍在处理中。你可以稍后重新打开查看结果，或从左侧菜单删除对话并中断生成。
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
          @keydown="onComposerKeydown"
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
  /* 侧栏固定，对话区吃掉全部剩余宽度，撑满整屏。 */
  grid-template-columns: 248px minmax(0, 1fr);
  /* 显式让行可收缩，否则grid行按内容高度撑开，矮窗口仍会页面滚动。 */
  grid-template-rows: minmax(0, 1fr);
  border: 1px solid var(--color-border-2);
  background: white;
  border-radius: 2px;
  /* 高度完全由整屏flex链决定，不再用视口常量估算。 */
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow: hidden;
}
.coach-sessions {
  padding: 20px;
  border-right: 1px solid var(--color-border-2);
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 0;
  overflow: hidden;
}
.coach-sessions > :not(nav) {
  flex-shrink: 0;
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
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  flex-direction: column;
  gap: 8px;
}
.coach-session-item {
  position: relative;
  flex-shrink: 0;
  border-radius: 2px;
  background: var(--color-fill-1);
}
.coach-session-item > a {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  padding: 12px 38px 12px 12px;
}
.coach-session-item > a > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.coach-session-item.is-current {
  background: rgb(var(--primary-1));
}
.coach-session-item.is-current > a {
  color: rgb(var(--primary-6));
}
.coach-session-menu {
  position: absolute;
  right: 4px;
  top: 8px;
  opacity: 0;
  pointer-events: none;
}
.coach-session-item:hover .coach-session-menu,
.coach-session-item:focus-within .coach-session-menu,
.coach-session-menu.arco-dropdown-open {
  opacity: 1;
  pointer-events: auto;
}
.coach-session-delete,
.coach-session-delete :deep(.arco-dropdown-option-icon) {
  color: rgb(var(--danger-6));
}
@media (hover: none) {
  .coach-session-menu {
    opacity: 1;
    pointer-events: auto;
  }
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
  width: 66.6667%;
  max-width: 100%;
  box-sizing: border-box;
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
/* 流式中的文字轻微淡化，读完后再转为正式样式。 */
.coach-message.is-streaming {
  opacity: 0.9;
}
/* 待确认的消息略淡化，让"还没真正送达"可见但不刺眼。 */
.coach-message.is-pending {
  opacity: 0.66;
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
  .coach-message {
    width: 100%;
  }
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
