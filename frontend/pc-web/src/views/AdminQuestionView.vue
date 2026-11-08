<script setup lang="ts">
import { computed, onBeforeUnmount, watch, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import {
  createPublicBankApi,
  notifyError,
  useAuthStore,
  type PublicReview,
  type PublicDraft,
} from '@quest/shared'
const auth = useAuthStore(),
  api = createPublicBankApi(auth),
  route = useRoute(),
  router = useRouter()
const review = ref<PublicReview | null>(null),
  draft = ref<PublicDraft | null>(null),
  saved = ref(''),
  busy = ref(false),
  error = ref('')
const knowledge = ref('')
const questionNo = ref('')
const savedQuestionNo = ref('')
const questionNoError = computed(() => {
  const value = questionNo.value.trim()
  return value && (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 2147483647)
    ? '题号须为1至2147483647的整数'
    : ''
})
let alive = true,
  revision = 0
onBeforeUnmount(() => {
  alive = false
})
const dirty = computed(
  () =>
    !!draft.value &&
    (JSON.stringify(draft.value) !== saved.value ||
      questionNo.value !== savedQuestionNo.value ||
      knowledge.value !== draft.value.knowledge_points.join(', ')),
)
const canEdit = computed(() => !busy.value && review.value?.status !== 'published')
const canConfirm = computed(
  () =>
    !busy.value &&
    !dirty.value &&
    review.value?.status === 'draft' &&
    review.value.importedBy === auth.user?.id,
)
const canPublish = computed(
  () => !busy.value && !dirty.value && review.value?.status === 'confirmed',
)
const warningLabels: Record<string, string> = {
  ai_answer_requires_review: 'AI建议答案，请核对',
  importer_confirmation_required: '需要导入人确认',
  reference_model_answer_conflict: '原答案与模型建议不一致',
  web_evidence_unavailable: '尚无可用检索依据',
  missing_reference_answer: '原材料未提供答案',
  page_contains_images_unassigned: '原图尚未关联，需核对',
}
function accept(value: PublicReview) {
  review.value = value
  draft.value = structuredClone(value.draft)
  saved.value = JSON.stringify(value.draft)
  questionNo.value = value.draft.source?.question_no?.toString() ?? ''
  savedQuestionNo.value = questionNo.value
  knowledge.value = value.draft.knowledge_points.join(', ')
}
async function load() {
  const current = ++revision
  review.value = null
  draft.value = null
  error.value = ''
  busy.value = false
  try {
    const value = await api.review(String(route.params.id))
    if (alive && current === revision) accept(value)
  } catch (e) {
    if (alive && current === revision) {
      error.value = '加载失败'
      notifyError(e)
    }
  }
}
async function action(kind: 'save' | 'confirm' | 'publish' | 'withdraw' | 'deleteQuestion') {
  if (busy.value || !review.value || !draft.value) return
  if ((kind === 'confirm' && !canConfirm.value) || (kind === 'publish' && !canPublish.value)) return
  if (kind === 'save' && (!canEdit.value || questionNoError.value)) return
  if (kind === 'withdraw' && review.value.status !== 'published') return
  if (kind === 'deleteQuestion' && review.value.status === 'published') return
  const current = revision
  busy.value = true
  try {
    if (kind === 'deleteQuestion') {
      const bankId = review.value.bankId
      await api.deleteQuestion(review.value.id, review.value.version)
      if (alive && current === revision) {
        Message.success('题目已删除')
        await router.push(`/admin/banks/${encodeURIComponent(bankId)}`)
      }
      return
    }
    let value: PublicReview
    if (kind === 'save') {
      const next = JSON.parse(JSON.stringify(draft.value)) as PublicDraft
      next.knowledge_points = knowledge.value
        .split(/[,，]/)
        .map((v) => v.trim())
        .filter(Boolean)
      next.source = {
        ...next.source,
        question_no: questionNo.value.trim() ? Number(questionNo.value.trim()) : null,
      }
      next.answer = { ...next.answer, source: 'manual', confidence: null }
      next.status = 'draft'
      next.quality = { review_status: 'pending' }
      value = await api.edit(review.value.id, review.value.version, next)
    } else value = await api[kind](review.value.id, review.value.version)
    if (alive && current === revision) {
      accept(value)
      Message.success(
        kind === 'save'
          ? '草稿已保存，请重新确认'
          : kind === 'confirm'
            ? '正确性已确认，可审核发布'
            : kind === 'withdraw'
              ? '已下架，请重新确认后发布'
              : '题目已发布',
      )
    }
  } catch (e) {
    if (alive && current === revision) notifyError(e)
  } finally {
    if (alive && current === revision) busy.value = false
  }
}
function safeLink(value: string) {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password
      ? url.href
      : undefined
  } catch {
    return undefined
  }
}
watch(
  () => (route.name === 'AdminQuestionView' ? route.params.id : undefined),
  (id) => {
    if (id && review.value?.id !== id) void load()
  },
  { immediate: true },
)
</script>
<template>
  <a-card title="题目审核" class="admin-users-card" :bordered="false">
    <a-empty v-if="error" description="题目加载失败，请返回题库后重试" />
    <template v-if="review && draft">
      <div class="user-toolbar">
        <span
          >版本 {{ review.version }} ·
          {{
            review.status === 'published'
              ? '已发布'
              : review.status === 'confirmed'
                ? '已确认'
                : '未确认'
          }}</span
        ><a-space
          ><template v-if="review.status !== 'published'"
            ><a-button
              data-test="save-question"
              :disabled="!canEdit || !dirty || busy"
              :loading="busy"
              @click="action('save')"
              >保存修改</a-button
            ><a-popconfirm
              content="已对照原材料和检索依据，确认当前答案及解析正确？"
              @ok="action('confirm')"
              ><a-button data-test="confirm-question" :disabled="!canConfirm"
                >确认正确性</a-button
              ></a-popconfirm
            ><a-popconfirm content="确认发布当前已审核版本？" @ok="action('publish')"
              ><a-button data-test="publish-question" type="primary" :disabled="!canPublish"
                >发布</a-button
              ></a-popconfirm
            >
            <a-popconfirm
              content="确认删除当前题目？"
              :disabled="busy"
              @ok="action('deleteQuestion')"
            >
              <a-button data-test="delete-question" type="text" status="danger" :disabled="busy"
                >删除</a-button
              >
            </a-popconfirm>
          </template>
          <a-popconfirm
            v-else
            content="确认下架当前题目？下架后须重新确认才能发布。"
            :disabled="busy"
            @ok="action('withdraw')"
          >
            <a-button data-test="withdraw-question" :disabled="busy" :loading="busy">下架</a-button>
          </a-popconfirm>
        </a-space>
      </div>
      <a-space wrap
        ><a-tag v-for="warning in review.evidence.warnings || []" :key="warning" color="orange">{{
          warningLabels[warning] || '材料需进一步核对'
        }}</a-tag></a-space
      >
      <div class="question-review-grid">
        <section>
          <h3>原材料</h3>
          <pre class="source-text">{{ review.sourceText }}</pre>
          <h3>答案对照</h3>
          <p>
            原答案：{{ review.evidence.provenance?.reference_answer_keys?.join('、') || '未提供' }}
          </p>
          <p>
            模型建议：{{ review.evidence.provenance?.model_answer_keys?.join('、') || '未提供' }}
          </p>
          <template v-if="review.evidence.provenance?.conflicting_model_explanation"
            ><h4>冲突解析（仅作核对依据）</h4>
            <pre class="source-text">{{
              review.evidence.provenance.conflicting_model_explanation
            }}</pre>
          </template>
          <h3>检索依据</h3>
          <p v-if="!review.evidence.provenance?.web_sources?.length">尚无可用检索依据</p>
          <article
            v-for="(source, index) in review.evidence.provenance?.web_sources || []"
            :key="index"
          >
            <a :href="safeLink(source.url)" target="_blank" rel="noopener noreferrer">{{
              source.title
            }}</a>
            <p>{{ source.excerpt }}</p>
          </article>
          <p v-if="review.evidence.provenance?.web_retrieved_at">
            检索时间：{{ review.evidence.provenance.web_retrieved_at }}
          </p>
        </section>
        <section>
          <a-form :model="draft" layout="vertical"
            ><a-form-item
              label="题号（选填）"
              :validate-status="questionNoError ? 'error' : undefined"
              :help="questionNoError"
              ><a-input
                v-model="questionNo"
                data-test="question-no-editor"
                :disabled="!canEdit"
                allow-clear /></a-form-item
            ><a-form-item label="题型"
              ><a-select v-model="draft.type" :disabled="!canEdit"
                ><a-option value="single_choice">单选题</a-option
                ><a-option value="multiple_choice">多选题</a-option></a-select
              ></a-form-item
            ><a-form-item label="题干"
              ><a-textarea
                v-model="draft.stem_md"
                data-test="stem-editor"
                :disabled="!canEdit"
                :max-length="5000"
                :auto-size="{ minRows: 3, maxRows: 12 }" /></a-form-item
            ><a-form-item
              v-for="option in draft.options"
              :key="option.key"
              :label="`选项 ${option.key}`"
              ><a-textarea v-model="option.text_md" :disabled="!canEdit" /></a-form-item
            ><a-form-item label="正确答案"
              ><a-select v-model="draft.answer.keys" multiple :disabled="!canEdit"
                ><a-option v-for="option in draft.options" :key="option.key" :value="option.key">{{
                  option.key
                }}</a-option></a-select
              ></a-form-item
            ><a-form-item label="解析"
              ><a-textarea
                v-model="draft.explanation_md"
                :disabled="!canEdit"
                :max-length="20000"
                :auto-size="{ minRows: 4, maxRows: 16 }" /></a-form-item
            ><a-form-item label="知识点编码（逗号分隔）"
              ><a-input v-model="knowledge" :disabled="!canEdit" /></a-form-item
            ><a-form-item label="难度"
              ><a-input-number
                v-model="draft.difficulty"
                :disabled="!canEdit"
                :min="1"
                :max="5" /></a-form-item
          ></a-form>
        </section>
      </div>
    </template>
  </a-card>
</template>
<style scoped>
.question-review-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 24px;
  margin-top: 16px;
}
.question-review-grid section {
  min-width: 0;
}
.source-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-sans);
  line-height: 1.8;
  background: var(--color-fill-1);
  padding: 16px;
}
article {
  border-bottom: 1px solid var(--color-border-2);
  overflow-wrap: anywhere;
}
article p {
  white-space: pre-wrap;
}
@media (max-width: 1000px) {
  .question-review-grid {
    grid-template-columns: 1fr;
  }
}
</style>
