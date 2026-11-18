<script setup lang="ts">
import { IconAttachment, IconFile, IconMessage } from '@arco-design/web-vue/es/icon'
import {
  createPrivateQuestionsApi,
  notifyError,
  useAuthStore,
  type PrivateResult,
} from '@quest/shared'
import SafeMarkdown from './SafeMarkdown.vue'
withDefaults(
  defineProps<{ result: PrivateResult; showConversation?: boolean; showIssues?: boolean }>(),
  { showConversation: false, showIssues: true },
)
function safeUrl(value: string) {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
      ? url.href
      : undefined
  } catch {
    return undefined
  }
}
const api = createPrivateQuestionsApi(useAuthStore())
async function download(id: string, name: string) {
  try {
    const body = await api.file(id)
    const url = URL.createObjectURL(body)
    const link = document.createElement('a')
    link.href = url
    link.download = name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    notifyError(error)
  }
}
</script>
<template>
  <div class="private-question-content">
    <SafeMarkdown :source="result.candidate.stem_md" />
    <div v-for="option in result.candidate.options" :key="option.key" class="private-option">
      <strong>{{ option.key }}.</strong><SafeMarkdown :source="option.text_md" />
    </div>
    <p v-if="result.candidate.personal_keys?.length">
      原材料中的个人作答：{{ result.candidate.personal_keys.join('、') }}
    </p>
    <p>
      原答案：{{ result.candidate.original_keys?.join('、') || '未提供' }} ·
      {{
        result.answer_source === 'original_unverified'
          ? '保留的原答案（未核实）'
          : result.answer_source === 'user_confirmed'
            ? '你已选择的答案'
            : 'AI 校对建议'
      }}：{{ result.answer_keys?.join('、') || '待补全' }}
    </p>
    <SafeMarkdown v-if="result.explanation_md" :source="result.explanation_md" />
    <div v-if="result.memory_tip" class="private-memory">
      <strong>记忆方法</strong><SafeMarkdown :source="result.memory_tip" />
    </div>
    <ul
      v-if="showIssues && result.status === 'needs_attention' && result.issues?.length"
      class="private-issues"
    >
      <li v-for="issue in result.issues" :key="issue">{{ issue }}</li>
    </ul>
    <details v-if="result.web_sources?.length">
      <summary>校对参考来源（不等于官方核验）</summary>
      <ul>
        <li v-for="source in result.web_sources" :key="source.url">
          <a :href="safeUrl(source.url)" target="_blank" rel="noopener noreferrer">{{
            source.title || source.url
          }}</a>
        </li>
      </ul>
    </details>
    <footer
      v-if="result.source?.asset_id || (showConversation && result.source?.session_id)"
      class="question-footer"
    >
      <div v-if="result.source?.filename" class="question-source">
        <IconFile aria-hidden="true" />
        <span class="source-filename" :title="result.source.filename">{{
          result.source.filename
        }}</span>
        <span v-if="result.candidate.page_numbers?.length" class="source-pages"
          >第 {{ result.candidate.page_numbers.join('、') }} 页</span
        >
      </div>
      <div class="question-actions">
        <a-button
          v-if="result.source?.asset_id"
          type="secondary"
          size="small"
          @click="download(result.source.asset_id, result.source.filename || '原件')"
        >
          <template #icon><IconAttachment /></template>查看原始附件
        </a-button>
        <RouterLink
          v-if="showConversation && result.source?.session_id"
          v-slot="{ href, navigate }"
          custom
          :to="{ path: '/practice/agent', query: { session: result.source.session_id } }"
        >
          <a-button :href="href" type="outline" size="small" @click="navigate"
            ><template #icon><IconMessage /></template>回到 Agent 对话</a-button
          >
        </RouterLink>
      </div>
    </footer>
  </div>
</template>
<style scoped>
.question-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 20px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-2);
}
.question-source {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1 1 180px;
  min-width: 0;
  color: var(--color-text-3);
  font-size: 12px;
}
.question-source > .arco-icon {
  flex-shrink: 0;
  font-size: 15px;
}
.source-filename {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-pages {
  flex-shrink: 0;
}
.question-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-left: auto;
}
@media (max-width: 640px) {
  .question-source {
    flex-basis: 100%;
  }
  .question-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
.private-option {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
p,
details {
  color: var(--color-text-2);
  font-size: 13px;
  line-height: 1.7;
}
.private-memory {
  padding: 12px 16px;
  margin: 12px 0;
  background: var(--color-fill-1);
  border-radius: 2px;
}
.private-issues {
  color: rgb(var(--warning-7));
  font-size: 13px;
}
</style>
