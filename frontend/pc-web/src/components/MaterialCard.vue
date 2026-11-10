<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import {
  createMaterialsApi,
  notifyError,
  subjectLabel,
  useAuthStore,
  type Material,
} from '@quest/shared'
import SafeMarkdown from './SafeMarkdown.vue'

const props = defineProps<{ material: Material }>()
const emit = defineEmits<{ update: [value: Material] }>()
const api = createMaterialsApi(useAuthStore())
const previewing = ref(false),
  previewBusy = ref(false),
  downloading = ref(false),
  retrying = ref(false)
const markdown = ref('')
let alive = true
onBeforeUnmount(() => {
  alive = false
})
const statusText = computed(
  () =>
    ({ queued: '排队中', processing: '生成中', ready: '已完成', failed: '生成失败' })[
      props.material.status
    ],
)
const created = computed(() =>
  new Date(props.material.createdAt).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
  }),
)
async function preview() {
  if (previewBusy.value || props.material.status !== 'ready') return
  previewBusy.value = true
  try {
    const value = await api.get(props.material.id)
    if (!alive) return
    if (value.status !== 'ready' || typeof value.markdown !== 'string')
      throw new Error('资料尚未完成，请刷新后重试')
    markdown.value = value.markdown
    previewing.value = true
  } catch (cause) {
    if (alive) notifyError(cause)
  } finally {
    if (alive) previewBusy.value = false
  }
}
async function download() {
  if (downloading.value || props.material.status !== 'ready') return
  downloading.value = true
  try {
    const blob = await api.download(props.material.id)
    if (!alive) return
    const url = URL.createObjectURL(blob)
    try {
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `${props.material.title.replace(/[\\/:*?"<>|\p{Cc}]/gu, '_').slice(0, 100) || '学习资料'}.md`
      document.body.append(anchor)
      try {
        anchor.click()
      } finally {
        anchor.remove()
      }
    } finally {
      URL.revokeObjectURL(url)
    }
  } catch (cause) {
    if (alive) notifyError(cause)
  } finally {
    if (alive) downloading.value = false
  }
}
async function retry() {
  if (retrying.value || props.material.status !== 'failed') return
  retrying.value = true
  try {
    const value = await api.retry(props.material.id, { requestId: crypto.randomUUID() })
    if (alive) emit('update', value)
  } catch (cause) {
    if (alive) notifyError(cause)
  } finally {
    if (alive) retrying.value = false
  }
}
</script>
<template>
  <article class="material-card" :data-material-id="material.id">
    <header>
      <div class="material-title">
        <h2>{{ material.title }}</h2>
        <a-tag>Markdown</a-tag>
      </div>
      <a-tag
        :color="
          material.status === 'failed' ? 'red' : material.status === 'ready' ? 'green' : 'cyan'
        "
        role="status"
        >{{ statusText }}</a-tag
      >
    </header>
    <p class="material-meta">{{ subjectLabel(material.subject) }} · {{ created }}</p>
    <p class="material-source">
      来源：<span v-if="material.sourceDeleted" aria-disabled="true"
        >{{ material.sourceTitle }}（已删除）</span
      ><RouterLink
        v-else
        :to="{ path: '/practice/agent', query: { session: material.sessionId } }"
        >{{ material.sourceTitle }}</RouterLink
      >
    </p>
    <p v-if="material.status === 'failed'" class="material-error">
      {{ material.error || '本次生成未完成，请手动重试。' }}
    </p>
    <p
      v-else-if="material.status === 'queued' || material.status === 'processing'"
      class="material-meta"
    >
      整理完成后可预览与下载，你可以继续对话。
    </p>
    <div class="material-actions">
      <template v-if="material.status === 'ready'">
        <a-button data-test="preview-material" size="small" :loading="previewBusy" @click="preview"
          >预览</a-button
        >
        <a-button
          data-test="download-material"
          size="small"
          type="primary"
          :loading="downloading"
          @click="download"
          >下载 Markdown</a-button
        >
      </template>
      <a-button
        v-if="material.status === 'failed'"
        data-test="retry-material"
        size="small"
        :loading="retrying"
        @click="retry"
        >重新整理</a-button
      >
    </div>
    <a-modal
      v-model:visible="previewing"
      :title="material.title"
      :footer="false"
      width="min(900px, 94vw)"
      unmount-on-close
    >
      <SafeMarkdown class="material-preview" :source="markdown" />
    </a-modal>
  </article>
</template>
<style scoped>
.material-card {
  padding: 20px;
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
  background: var(--color-bg-2);
  min-width: 0;
  overflow-wrap: anywhere;
}
.material-card header,
.material-title,
.material-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.material-card header {
  justify-content: space-between;
}
.material-title h2 {
  margin: 0;
  font-size: 16px;
}
.material-meta,
.material-source {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.7;
}
.material-source a {
  color: rgb(var(--primary-6));
}
.material-error {
  color: rgb(var(--danger-6));
  font-size: 13px;
}
.material-preview {
  max-height: 65vh;
  overflow: auto;
  overflow-wrap: anywhere;
}
</style>
