<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconDelete, IconMore, IconRefresh } from '@arco-design/web-vue/es/icon'
import {
  ApiError,
  createCoachApi,
  createPrivateQuestionsApi,
  type PrivateAsset,
  type PrivateImportJob,
  createMaterialsApi,
  isActiveMaterial,
  type Material,
  notifyError,
  streamTurn,
  subjectLabel,
  useAuthStore,
  type CoachSession,
  type CoachSessionSummary,
  type CoachTurn,
} from '@quest/shared'
import PrivateImportCard from '../components/PrivateImportCard.vue'
import SafeMarkdown from '../components/SafeMarkdown.vue'
import CoachQuestionCard from '../components/CoachQuestionCard.vue'
import MaterialCard from '../components/MaterialCard.vue'
import CoachMemoryPanel from '../components/CoachMemoryPanel.vue'
import { useCoachRecovery } from '../composables/coach-recovery'
import { failedReplyRetry } from '../composables/coach-retry'
import { groupCoachMaterials } from '../composables/coach-materials'
import { useMaterialPolling } from '../composables/material-polling'
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
const privateApi = createPrivateQuestionsApi(auth)
const attachments = ref<PrivateAsset[]>([]),
  uploading = ref(false),
  fileInput = ref<HTMLInputElement>()
const privateImports = computed(() => {
  const pending = new Map((session.value?.privateImports ?? []).map((job) => [job.id, job]))
  const messages = new Map<string, PrivateImportJob[]>()
  for (const message of session.value?.messages ?? []) {
    if (message.role !== 'assistant' || !message.privateImportId) continue
    const job = pending.get(message.privateImportId)
    if (job) {
      messages.set(message.id, [job])
      pending.delete(job.id)
    }
  }
  // 旧消息被长会话裁剪后，其任务仍保留在当前消息之前。
  return { messages, earlier: [...pending.values()] }
})
const replyRetry = computed(() => failedReplyRetry(session.value))
async function retryReply() {
  const retry = replyRetry.value
  if (!retry || loading.value) return
  await send('chat', retry.text, retry.attachments)
}
function updatePrivateJob(job: PrivateImportJob) {
  if (!session.value || session.value.id !== job.session_id) return
  session.value.privateImports = (session.value.privateImports ?? []).map((item) =>
    item.id === job.id ? job : item,
  )
}
async function uploadFiles(files: File[]) {
  if (uploading.value || busy.value || !session.value || session.value.processing) return
  if (files.length + attachments.value.length > 4) {
    notifyError(new Error('每条消息最多4个附件'))
    return
  }
  if (
    files.some(
      (file) =>
        file.size === 0 ||
        file.size > 8 * 1024 * 1024 ||
        !/\.(png|jpe?g|webp|pdf|txt)$/i.test(file.name),
    )
  ) {
    notifyError(new Error('请选择不超过8MiB的PNG、JPEG、WebP、PDF或UTF-8文本'))
    return
  }
  const version = revision
  uploading.value = true
  try {
    for (const file of files) {
      const asset = await privateApi.upload(file, crypto.randomUUID())
      if (!alive || version !== revision) return
      attachments.value.push(asset)
    }
  } catch (error) {
    if (alive && version === revision) notifyError(error)
  } finally {
    if (alive && version === revision) uploading.value = false
  }
}
function onFiles(event: Event) {
  const input = event.target as HTMLInputElement
  void uploadFiles(Array.from(input.files ?? []))
  input.value = ''
}
function onPaste(event: ClipboardEvent) {
  const images = Array.from(event.clipboardData?.files ?? [])
  if (images.length) {
    event.preventDefault()
    void uploadFiles(images)
    return
  }
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (text.length > 2000) {
    event.preventDefault()
    void uploadFiles([new File([text], '题目文本.txt', { type: 'text/plain' })])
  }
}
const materialApi = createMaterialsApi(auth)
const materials = ref<Material[]>([]),
  materialBusy = ref(false)
const groupedMaterials = computed(() =>
  groupCoachMaterials(session.value?.messages ?? [], materials.value),
)
function updateMaterial(value: Material) {
  const index = materials.value.findIndex((item) => item.id === value.id)
  if (index < 0) materials.value.push(value)
  else
    materials.value[index] = {
      ...value,
      sourceMessageId: value.sourceMessageId ?? materials.value[index]?.sourceMessageId,
    }
}
const { paused: materialPaused, resume: resumeMaterials } = useMaterialPolling(
  () => materials.value,
  updateMaterial,
)
async function createMaterial() {
  if (
    materialBusy.value ||
    !session.value ||
    loading.value ||
    busy.value ||
    session.value.processing ||
    materials.value.some(isActiveMaterial)
  )
    return
  const version = revision,
    id = session.value.id
  const sourceMessageId = session.value.messages.at(-1)?.id
  materialBusy.value = true
  try {
    const value = await materialApi.create(id, { requestId: crypto.randomUUID() })
    if (!alive || version !== revision) return
    updateMaterial({
      ...value,
      sourceMessageId: value.sourceMessageId ?? sourceMessageId,
    })
    resumeMaterials()
    // 按钮创建也由服务端写入资料通知；读回该消息，刷新后保持相同位置。
    try {
      const updated = await api.get(id, true)
      if (!alive || version !== revision) return
      session.value = updated
      selected.value = selectionFor(updated)
    } catch (cause) {
      if (alive && version === revision) notifyError(cause)
    }
    void scroll(version)
  } catch (cause) {
    if (alive && version === revision) notifyError(cause)
  } finally {
    if (alive && version === revision) materialBusy.value = false
  }
}
const { paused: recoveryPaused, resume: resumeRecovery } = useCoachRecovery(
  () => session.value,
  () => busy.value,
  (id, signal) => api.get(id, true, signal),
  (value) => {
    session.value = value
    selected.value = selectionFor(value)
    for (const item of value.materials ?? []) {
      const existing = materials.value.find((material) => material.id === item.id)
      if (!existing || item.updatedAt > existing.updatedAt) updateMaterial(item)
    }
  },
)
const messages = ref<HTMLElement>()
const deleting = ref<string | null>(null)
let listRevision = 0
// 仅在服务端 state 确认之前展示乐观消息。
const optimistic = ref<{ id: string; role: 'user'; text: string } | null>(null)
// 临时文本不写入会话；done 必须整体替换为持久化结果。
const toolRunning = ref<'' | 'search_web' | 'fetch_page' | 'get_current_time'>('')
const streaming = ref('')
let alive = true,
  revision = 0
let active: { requestId: string; controller: AbortController } | null = null
function cancelPending() {
  attachments.value = []
  uploading.value = false
  materials.value = []
  materialBusy.value = false
  resumeMaterials()
  revision++
  active?.controller.abort()
  active = null
  optimistic.value = null
  streaming.value = ''
  toolRunning.value = ''
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
    (item) =>
      (item.action === 'next' || item.action === 'chat') &&
      (!item.required_feature || session.value?.capabilities?.[item.required_feature] === true),
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
    const value =
      route.query.draft === '1'
        ? await api.create(
            id,
            'chat',
            typeof route.query.topic === 'string' ? route.query.topic : undefined,
          )
        : await api.get(id, true)
    if (alive && currentRevision === revision) {
      session.value = value
      materials.value = value.materials ?? []
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
async function send(
  action: 'chat' | 'next' | 'answer',
  text = draft.value,
  retryAttachments?: string[],
) {
  if (
    busy.value ||
    materialBusy.value ||
    uploading.value ||
    !session.value ||
    session.value.processing ||
    (action === 'chat' && !text.trim() && !attachments.value.length) ||
    (action === 'answer' && !selected.value.length)
  )
    return
  const currentRevision = revision,
    id = session.value.id,
    initialMessageCount = session.value.messages.length
  const payload: CoachTurn = {
    requestId: crypto.randomUUID(),
    action,
    text: action === 'chat' ? text.trim() || '已发送附件' : '',
    ...(action === 'chat' && (retryAttachments ?? attachments.value).length
      ? { attachments: retryAttachments ?? attachments.value.map((item) => item.id) }
      : {}),
  }
  if (session.value.persisted === false)
    payload.draft = { topicId: session.value.topic?.id ?? null }
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
  if (action === 'chat' && retryAttachments === undefined) draft.value = ''
  void scroll(currentRevision)
  let confirmed = false
  const accept = (value: CoachSession) => {
    if (!isCurrent()) return
    const follow = nearBottom()
    session.value = value
    if (value.persisted !== false && route.query.draft === '1') {
      const query = { ...route.query }
      delete query.draft
      delete query.topic
      void router.replace({ path: route.path, query })
    }
    // 普通轮次快照可能早于资料轮询；不让旧状态覆盖已经完成的卡片。
    for (const item of value.materials ?? []) {
      const existing = materials.value.find((material) => material.id === item.id)
      if (
        !existing ||
        item.updatedAt > existing.updatedAt ||
        (item.updatedAt === existing.updatedAt && isActiveMaterial(existing))
      )
        updateMaterial(item)
    }
    optimistic.value = null
    selected.value = selectionFor(value)
    confirmed = true
    if (payload.attachments)
      attachments.value = attachments.value.filter(
        (item) => !payload.attachments!.includes(item.id),
      )
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
        onToolStatus: (status) => {
          assertCurrent()
          if (isCurrent()) toolRunning.value = status.status === 'running' ? status.tool : ''
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
          if (isCurrent()) {
            streaming.value = ''
            toolRunning.value = ''
          }
        },
        onDone: (value) => {
          assertCurrent()
          if (!isCurrent()) return
          streaming.value = ''
          toolRunning.value = ''
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
    toolRunning.value = ''
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
        for (const item of value.materials ?? []) {
          const existing = materials.value.find((material) => material.id === item.id)
          if (!existing || item.updatedAt > existing.updatedAt) updateMaterial(item)
        }
        selected.value = selectionFor(value)
        if (confirmed && payload.attachments)
          attachments.value = attachments.value.filter(
            (item) => !payload.attachments!.includes(item.id),
          )
      } catch (recoveryError) {
        if (
          isCurrent() &&
          !(payload.draft && recoveryError instanceof ApiError && recoveryError.status === 404)
        )
          notifyError(recoveryError)
      }
    }
    if (isCurrent() && action === 'chat' && !confirmed) draft.value = text
  } finally {
    if (isCurrent()) {
      optimistic.value = null
      streaming.value = ''
      toolRunning.value = ''
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
async function newChat(topicId?: string) {
  if (busy.value) return
  const expectedRevision = revision
  busy.value = true
  try {
    const value = await api.create(crypto.randomUUID(), 'chat', topicId)
    if (alive && revision === expectedRevision)
      await router.push({
        path: '/practice/agent',
        query: {
          session: value.id,
          ...(value.persisted === false
            ? { draft: '1', ...(topicId ? { topic: topicId } : {}) }
            : {}),
        },
      })
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
        <a-button size="small" :disabled="busy" @click="newChat()">新对话</a-button>
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
          <span
            >{{ session ? subjectLabel(session.subject) : '答疑与练习，在同一段对话中'
            }}{{ session?.topic ? ` · ${session.topic.title}` : '' }}</span
          >
        </div>
        <CoachMemoryPanel
          v-if="session && session.persisted !== false"
          :session="session"
          :disabled="loading || busy || session.processing"
          @update="(value) => (session = value)"
          @continue="newChat"
        />
        <a-button
          v-if="session && session.persisted !== false && session.capabilities?.materials !== false"
          data-test="create-material"
          :loading="materialBusy"
          :disabled="loading || busy || session.processing || materials.some(isActiveMaterial)"
          @click="createMaterial"
          >整理学习资料</a-button
        >
      </header>
      <div class="coach-loading" :aria-busy="loading">
        <div v-if="loading" class="coach-loading-veil" role="status">正在加载对话…</div>
        <div ref="messages" class="coach-messages" aria-live="polite">
          <a-empty v-if="error" description="对话加载失败，请重新选择会话" />
          <div v-else-if="!session" class="coach-welcome">
            <span>序</span>
            <h2>说说你想学什么。</h2>
            <p>可以从学习空间开始刷题，也可以新建对话，聊聊一个困惑的知识点。</p>
            <a-button type="primary" :disabled="busy" @click="newChat()">开始对话</a-button>
          </div>
          <article v-if="groupedMaterials.earlier.length" class="coach-message assistant">
            <span class="message-speaker">知序 · 更早的学习资料</span>
            <MaterialCard
              v-for="material in groupedMaterials.earlier"
              :key="material.id"
              :material="material"
              @update="updateMaterial"
            />
          </article>
          <article v-if="privateImports.earlier.length" class="coach-message assistant">
            <span class="message-speaker">知序</span>
            <p class="earlier-imports-label">更早的题目导入</p>
            <PrivateImportCard
              v-for="job in privateImports.earlier"
              :key="job.id"
              :job="job"
              @update="updatePrivateJob"
            />
          </article>
          <article
            v-for="message in session?.messages || []"
            :key="message.id"
            class="coach-message"
            :class="message.role"
          >
            <span class="message-speaker">{{ message.role === 'assistant' ? '知序' : '我' }}</span>
            <a-space v-if="message.attachments?.length" wrap
              ><a-tag v-for="asset in message.attachments" :key="asset.id">{{
                asset.filename
              }}</a-tag></a-space
            >
            <SafeMarkdown class="message-prose" :source="message.text" />
            <div v-if="replyRetry?.messageId === message.id" class="message-failure-actions">
              <a-tooltip content="重新生成回复；已保存的作答不会重复提交">
                <a-button
                  type="text"
                  size="small"
                  aria-label="重试回复"
                  data-test="retry-reply"
                  :loading="busy"
                  :disabled="loading || busy || uploading || !!session?.processing"
                  @click="retryReply"
                  ><template #icon><IconRefresh /></template
                ></a-button>
              </a-tooltip>
            </div>
            <PrivateImportCard
              v-for="job in privateImports.messages.get(message.id) ?? []"
              :key="job.id"
              :job="job"
              @update="updatePrivateJob"
            />
            <div v-if="groupedMaterials.messages.has(message.id)" class="coach-message-materials">
              <MaterialCard
                v-for="material in groupedMaterials.messages.get(message.id)"
                :key="material.id"
                :material="material"
                @update="updateMaterial"
              />
            </div>
            <details v-if="message.sources?.length" class="coach-sources">
              <summary>外部资料，未核验 · {{ message.sources.length }} 个来源</summary>
              <ul>
                <li v-for="source in message.sources" :key="source.url">
                  <a :href="source.url" target="_blank" rel="noopener noreferrer">{{
                    source.title || source.url
                  }}</a>
                  <time :datetime="source.retrieved_at">{{
                    new Date(source.retrieved_at).toLocaleString('zh-CN', {
                      timeZone: 'Asia/Shanghai',
                    })
                  }}</time>
                </li>
              </ul>
            </details>
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
          <a-button v-if="materialPaused" size="small" @click="resumeMaterials"
            >刷新资料状态</a-button
          >
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
          <p v-if="busy" class="coach-thinking" role="status">
            {{
              toolRunning === 'get_current_time'
                ? '正在确认日期…'
                : toolRunning === 'fetch_page'
                  ? '正在读取网页…'
                  : toolRunning
                    ? '正在检索…'
                    : '知序正在思考…'
            }}
          </p>
          <p v-if="session?.processing && !busy" class="coach-thinking">
            {{
              recoveryPaused ? '会话状态更新已暂停。' : '上一条消息仍在处理中，正在自动查询进度…'
            }}
            <a-button v-if="recoveryPaused" type="text" size="small" @click="resumeRecovery"
              >查询进度</a-button
            >
          </p>
        </div>
      </div>
      <form v-if="session" class="coach-composer" @submit.prevent="send('chat')">
        <a-space v-if="attachments.length" wrap
          ><a-tag
            v-for="asset in attachments"
            :key="asset.id"
            :closable="!busy"
            @close="attachments = attachments.filter((item) => item.id !== asset.id)"
            >{{ asset.filename }}</a-tag
          ></a-space
        >
        <small v-if="attachments.length">导入时将使用已配置的图片识别与模型服务处理附件。</small>
        <label class="sr-only" for="coach-input">消息</label>
        <a-textarea
          id="coach-input"
          v-model="draft"
          placeholder="输入答案、说说思路，或追问一个知识点…"
          :max-length="2000"
          :auto-size="{ minRows: 2, maxRows: 5 }"
          :disabled="busy || session.processing"
          @keydown="onComposerKeydown"
          @paste="onPaste"
        />
        <div>
          <input
            ref="fileInput"
            type="file"
            hidden
            multiple
            accept=".png,.jpg,.jpeg,.webp,.pdf,.txt"
            @change="onFiles"
          />
          <a-button
            :loading="uploading"
            :disabled="busy || session.processing"
            @click="fileInput?.click()"
            >添加题目附件</a-button
          >
          <a-button
            html-type="submit"
            type="primary"
            :loading="busy"
            :disabled="(!draft.trim() && !attachments.length) || uploading || session.processing"
            >发送</a-button
          >
        </div>
      </form>
    </div>
  </section>
</template>
<style scoped>
.earlier-imports-label {
  margin: 8px 0 0;
  color: var(--color-text-3);
  font-size: 12px;
}
.coach-sources {
  margin-top: 12px;
  color: var(--color-text-3);
  font-size: 12px;
}
.coach-sources summary {
  cursor: pointer;
}
.coach-sources li {
  margin-top: 8px;
  overflow-wrap: anywhere;
}
.coach-sources time {
  display: block;
  margin-top: 2px;
}

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
  flex-wrap: wrap;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border-2);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.coach-conversation-head > div {
  flex: 1;
  min-width: 180px;
}
.coach-conversation-head h1 {
  overflow-wrap: anywhere;
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
.coach-message-materials {
  display: grid;
  gap: 12px;
  margin-top: 12px;
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
.message-failure-actions {
  margin-top: 8px;
}
.message-failure-actions :deep(.arco-btn) {
  color: var(--color-text-3);
}
.message-failure-actions :deep(.arco-btn:hover) {
  color: rgb(var(--primary-6));
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
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
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
