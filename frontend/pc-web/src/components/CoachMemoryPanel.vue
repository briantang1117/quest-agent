<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import {
  createCoachApi,
  notifyError,
  useAuthStore,
  type CoachSession,
  type LearningTopic,
  type LearningTopicInput,
} from '@quest/shared'
const props = defineProps<{ session: CoachSession; disabled: boolean }>()
const emit = defineEmits<{ update: [value: CoachSession]; continue: [topicId: string] }>()
const api = createCoachApi(useAuthStore())
const visible = ref(false),
  busy = ref(false),
  failed = ref(false)
const summary = ref(''),
  topics = ref<LearningTopic[]>([]),
  selected = ref<string | undefined>()
const editing = ref<LearningTopic | null>(null),
  creating = ref(false)
const form = reactive<LearningTopicInput>({
  subject: props.session.subject,
  title: '',
  goal: '',
  progress: '',
  questions: '',
  plan: '',
  revision: 0,
})
let alive = true,
  revision = 0
onBeforeUnmount(() => {
  alive = false
  revision++
})
watch(
  () => props.session.id,
  () => {
    revision++
    visible.value = false
    busy.value = false
  },
)
function edit(value: LearningTopic | null) {
  editing.value = value
  creating.value = !value
  Object.assign(
    form,
    value
      ? { ...value }
      : {
          subject: props.session.subject,
          title: '',
          goal: '',
          progress: '',
          questions: '',
          plan: '',
          revision: 0,
        },
  )
}
async function open() {
  const version = ++revision
  summary.value = props.session.memory?.summary ?? ''
  selected.value = props.session.topic?.id
  editing.value = props.session.topic ?? null
  creating.value = false
  if (editing.value) edit(editing.value)
  visible.value = true
  busy.value = true
  failed.value = false
  try {
    const value = await api.topics()
    if (alive && version === revision) {
      topics.value = value.list.filter((item) => item.subject === props.session.subject)
      const current = topics.value.find((item) => item.id === selected.value)
      if (current) edit(current)
    }
  } catch (error) {
    if (alive && version === revision) {
      failed.value = true
      notifyError(error)
    }
  } finally {
    if (alive && version === revision) busy.value = false
  }
}
async function run(action: () => Promise<void>) {
  if (busy.value || props.disabled) return
  const version = revision
  busy.value = true
  try {
    await action()
  } catch (error) {
    if (alive && version === revision) notifyError(error)
  } finally {
    if (alive && version === revision) busy.value = false
  }
}
function continueTopic() {
  const topic = props.session.topic
  if (!topic || busy.value || props.disabled) return
  visible.value = false
  emit('continue', topic.id)
}
async function saveSummary() {
  const version = revision,
    id = props.session.id
  await run(async () => {
    const value = await api.updateMemory(id, summary.value)
    if (!alive || version !== revision) return
    emit('update', value)
    Message.success(summary.value.trim() ? '会话摘要已保存' : '会话摘要已清空')
  })
}
async function bind() {
  const version = revision,
    id = props.session.id,
    topicId = selected.value ?? null
  await run(async () => {
    const value = await api.bindTopic(id, topicId)
    if (!alive || version !== revision) return
    emit('update', value)
    if (value.topic) edit(value.topic)
    else {
      editing.value = null
      creating.value = false
    }
    Message.success(topicId ? '已关联学习主题' : '已取消主题关联')
  })
}
const createRequest = ref(crypto.randomUUID())
async function saveTopic() {
  if (!form.title.trim()) {
    Message.warning('请填写学习主题名称')
    return
  }
  const version = revision,
    id = props.session.id,
    old = editing.value
  const body = {
    subject: form.subject,
    title: form.title.trim(),
    goal: form.goal,
    progress: form.progress,
    questions: form.questions,
    plan: form.plan,
    revision: form.revision,
  }
  await run(async () => {
    const value = old
      ? await api.updateTopic(old.id, body)
      : await api.createTopic({ ...body, requestId: createRequest.value })
    if (!alive || version !== revision) return
    if (!old) createRequest.value = crypto.randomUUID()
    topics.value = [value, ...topics.value.filter((item) => item.id !== value.id)]
    edit(value)
    selected.value = value.id
    const session = await api.bindTopic(id, value.id)
    if (!alive || version !== revision) return
    emit('update', session)
    Message.success('学习主题已保存并关联')
  })
}
async function removeTopic() {
  const topic = editing.value
  if (!topic) return
  const version = revision,
    id = props.session.id
  await run(async () => {
    await api.deleteTopic(topic.id)
    if (!alive || version !== revision) return
    topics.value = topics.value.filter((item) => item.id !== topic.id)
    selected.value = undefined
    editing.value = null
    creating.value = false
    const value = await api.bindTopic(id, null)
    if (alive && version === revision) {
      emit('update', value)
      Message.success('学习主题已删除')
    }
  })
}
</script>
<template>
  <a-button data-test="learning-memory" :disabled="disabled" @click="open">学习记忆</a-button>
  <a-drawer
    v-if="visible"
    :visible="visible"
    title="学习记忆"
    width="min(620px, 100vw)"
    :footer="false"
    :closable="!busy"
    :mask-closable="!busy"
    :esc-to-close="!busy"
    @cancel="visible = false"
  >
    <section class="memory-section">
      <h3>会话摘要<a-tag size="small">自动更新</a-tag></h3>
      <p>
        摘要会随完整回答自动更新，无需手动保存。记错或遗漏时，可在这里修改或清空，再点击“保存修改”。
      </p>
      <a-textarea
        v-model="summary"
        :max-length="4000"
        show-word-limit
        :auto-size="{ minRows: 4, maxRows: 10 }"
        :disabled="busy || disabled"
        placeholder="对话中会自动生成摘要，无需手动填写"
        aria-label="会话摘要"
      />
      <div class="memory-actions">
        <a-button :loading="busy" :disabled="disabled" @click="saveSummary">保存修改</a-button>
      </div>
    </section>
    <a-divider />
    <section class="memory-section">
      <h3>学习主题</h3>
      <p>
        选择并关联主题后，进度、疑问和计划会随完整回答自动更新，新对话可接着学习。只需在纠正档案时手动保存修改。
      </p>
      <a-alert v-if="failed" type="error">学习主题加载失败，请关闭后重新打开。</a-alert>
      <a-space direction="vertical" fill>
        <a-select
          v-model="selected"
          :disabled="busy || disabled || failed"
          placeholder="选择学习主题，不选则独立对话"
          allow-clear
          aria-label="学习主题"
          @change="
            (id) => {
              const topic = topics.find((item) => item.id === id)
              if (topic) edit(topic)
              else {
                editing = null
                creating = false
              }
            }
          "
        >
          <a-option v-for="item in topics" :key="item.id" :value="item.id">{{
            item.title
          }}</a-option>
        </a-select>
        <a-space wrap
          ><a-button :disabled="busy || disabled || failed" @click="bind">{{
            selected ? '关联主题' : '取消关联'
          }}</a-button
          ><a-button :disabled="busy || disabled || failed" @click="edit(null)">新建主题</a-button
          ><a-button v-if="session.topic" :disabled="busy || disabled" @click="continueTopic"
            >新对话继续此主题</a-button
          ></a-space
        >
      </a-space>
      <a-form
        v-if="editing || creating"
        :model="form"
        layout="vertical"
        class="topic-form"
        :disabled="busy || disabled"
        @submit-success="saveTopic"
      >
        <a-form-item label="主题名称"
          ><a-input v-model="form.title" :max-length="100"
        /></a-form-item>
        <a-form-item label="学习目标"
          ><a-textarea v-model="form.goal" :max-length="1500"
        /></a-form-item>
        <a-form-item label="当前进度"
          ><a-textarea
            v-model="form.progress"
            :max-length="1500"
            placeholder="记录已讨论内容，不将 AI 讲过等同于已经掌握"
        /></a-form-item>
        <a-form-item label="待解决问题"
          ><a-textarea v-model="form.questions" :max-length="1500"
        /></a-form-item>
        <a-form-item label="后续计划"
          ><a-textarea v-model="form.plan" :max-length="1500"
        /></a-form-item>
        <p v-if="editing?.source_session_id">
          档案包含 AI 归纳，请按实际学习情况核对。删除主题不删除原对话。
        </p>
        <div class="memory-actions">
          <a-popconfirm
            v-if="editing"
            content="删除此主题后将停止后续续接，原对话仍保留。确认删除？"
            @ok="removeTopic"
            ><a-button status="danger" :disabled="busy || disabled"
              >删除主题</a-button
            ></a-popconfirm
          ><a-button type="primary" html-type="submit" :loading="busy">{{
            creating ? '创建并关联' : '保存修改并关联'
          }}</a-button>
        </div>
      </a-form>
    </section>
  </a-drawer>
</template>
<style scoped>
.memory-section h3 {
  margin: 0 0 12px;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.memory-section p {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 16px;
}
.memory-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}
.topic-form {
  margin-top: 24px;
}
</style>
