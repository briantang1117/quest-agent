<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  createPrivateQuestionsApi,
  notifyError,
  useAuthStore,
  type PrivateImportJob,
} from '@quest/shared'
import PrivateQuestionContent from './PrivateQuestionContent.vue'
const props = defineProps<{ job: PrivateImportJob }>()
const emit = defineEmits<{ update: [value: PrivateImportJob] }>()
const api = createPrivateQuestionsApi(useAuthStore())
const value = ref(props.job),
  busy = ref(false),
  paused = ref(false)
let alive = true,
  revision = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  controller: AbortController | undefined
const active = computed(() => ['queued', 'processing'].includes(value.value.status))
const failedOnly = computed(
  () =>
    value.value.failed + value.value.source_failures > 0 &&
    !value.value.saved &&
    !value.value.needs_attention &&
    !value.value.duplicates,
)
const labels: Record<string, string> = {
  queued: '排队中',
  processing: '处理中',
  completed: '已完成',
  needs_attention: '部分内容需处理',
  failed: '处理失败',
  cancelled: '已取消',
}
function accept(next: PrivateImportJob) {
  value.value = next
  emit('update', next)
}
function schedule() {
  if (timer) clearTimeout(timer)
  if (alive && active.value && !paused.value) timer = setTimeout(poll, 2000)
}
function resume() {
  paused.value = false
  void poll()
}
async function poll() {
  const version = revision
  controller = new AbortController()
  try {
    const result = await api.job(value.value.id, controller.signal)
    if (alive && version === revision) {
      accept(result)
      schedule()
    }
  } catch (error) {
    if (alive && version === revision) {
      paused.value = true
      notifyError(error)
    }
  }
}
watch(
  () => props.job,
  (job) => {
    value.value = job
    paused.value = false
    schedule()
  },
)
watch(
  () => props.job.id,
  () => {
    revision++
    controller?.abort()
  },
)
schedule()
onBeforeUnmount(() => {
  alive = false
  revision++
  if (timer) clearTimeout(timer)
  controller?.abort()
})
async function action(operation: () => Promise<PrivateImportJob>) {
  if (busy.value) return
  const version = revision
  busy.value = true
  try {
    const result = await operation()
    if (alive && version === revision) {
      accept(result)
      paused.value = false
      schedule()
    }
  } catch (error) {
    if (alive && version === revision) notifyError(error)
  } finally {
    if (alive && version === revision) busy.value = false
  }
}
</script>
<template>
  <a-card class="private-import-card" title="私有题目导入" :bordered="true">
    <template #extra
      ><a-tag
        :color="
          value.status === 'completed'
            ? 'green'
            : value.status === 'needs_attention'
              ? 'orange'
              : 'gray'
        "
        >{{ failedOnly ? '导入失败' : labels[value.status] }}</a-tag
      ></template
    >
    <p v-if="active">
      {{
        value.stage === 'recognizing'
          ? '正在提取文字、识别题目…'
          : value.stage === 'checking'
            ? '正在独立解题并校对原答案…'
            : '正在准备下一步…'
      }}
      离开页面后可回来查看。
    </p>
    <p>
      已保存 {{ value.saved }} 题 · 待处理 {{ value.needs_attention }} 题 · 重复
      {{ value.duplicates }} 题 · 失败 {{ value.failed + value.source_failures }} 项
    </p>
    <a-alert
      v-for="failure in value.source_errors ?? []"
      :key="failure.id"
      type="error"
      class="import-error"
    >
      {{ failure.filename }}：{{ failure.message }}
    </a-alert>
    <a-alert
      v-for="item in value.items.filter((entry) => entry.status === 'failed')"
      :key="item.id"
      type="error"
      class="import-error"
    >
      本批第 {{ item.ordinal }} 题：{{
        item.error_message ||
        (item.error_code === 40240
          ? 'Token 余额不足，请联系管理员充值后重试。'
          : '本题处理失败，请检查服务状态后手动重试。')
      }}
    </a-alert>
    <ul v-if="value.warnings.length">
      <li v-for="warning in value.warnings" :key="warning">{{ warning }}</li>
    </ul>
    <p v-if="value.status === 'failed' || value.source_failures">
      部分步骤未完成，已保存的题目会保留。重试只处理未完成步骤，不自动重复调用。
    </p>
    <a-space wrap>
      <a-button v-if="paused" size="small" :loading="busy" @click="resume">刷新导入状态</a-button>
      <a-button
        v-if="value.status === 'failed' || (!active && (value.failed || value.source_failures))"
        size="small"
        :loading="busy"
        @click="action(() => api.control(value.id, 'retry'))"
        >重试未完成步骤</a-button
      >
      <a-button
        v-if="active"
        size="small"
        :disabled="busy"
        @click="action(() => api.control(value.id, 'cancel'))"
        >停止后续处理</a-button
      >
    </a-space>
    <details
      v-for="item in value.items.filter((entry) => entry.result)"
      :key="item.id"
      class="private-item"
      :open="item.status === 'needs_attention'"
    >
      <summary>
        本批第 {{ item.ordinal }} 题 ·
        {{
          item.status === 'ready'
            ? '已保存'
            : item.status === 'duplicate'
              ? '重复，已跳过'
              : item.status === 'excluded'
                ? '已排除练习'
                : '待核实'
        }}
      </summary>
      <PrivateQuestionContent v-if="item.result" :result="item.result" />
      <a-space v-if="item.status === 'needs_attention'" wrap>
        <a-button
          size="small"
          type="primary"
          :disabled="busy || item.result?.candidate.requires_image"
          @click="action(() => api.decide(item.id, value.session_id, 'use_suggestion'))"
          >采用建议答案</a-button
        >
        <a-button
          v-if="item.result?.candidate.original_keys.length"
          size="small"
          :disabled="busy"
          @click="action(() => api.decide(item.id, value.session_id, 'keep_original'))"
          >保留原答案</a-button
        >
        <a-button
          size="small"
          :disabled="busy"
          @click="action(() => api.decide(item.id, value.session_id, 'exclude'))"
          >先存着，不拿来刷</a-button
        >
      </a-space>
      <p v-if="item.status === 'needs_attention'">
        也可以在聊天中说明“本批第 {{ item.ordinal }} 题采用建议答案”或补充清晰题目后重新导入。
      </p>
    </details>
  </a-card>
</template>
<style scoped>
.import-error {
  margin: 12px 0;
}
.private-import-card {
  margin: 16px 0;
  border-radius: 2px;
}
p,
li {
  color: var(--color-text-2);
  line-height: 1.7;
  font-size: 13px;
}
.private-item {
  margin-top: 16px;
  border-top: 1px solid var(--color-border-2);
  padding-top: 12px;
}
summary {
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 12px;
}
</style>
