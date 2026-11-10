<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Message, type FormInstance } from '@arco-design/web-vue'
import {
  createSyllabusApi,
  notifyError,
  SYLLABUS_EXAMS,
  SYLLABUS_STATUSES,
  SYLLABUS_REVIEW_STATUSES,
  SYLLABUS_INDEX_STATUSES,
  syllabusJobActive,
  subjectLabel,
  useAuthStore,
  type SyllabusDetail,
  type SyllabusMetadata,
  type SyllabusSection,
  type SyllabusUpdate,
} from '@quest/shared'
import SyllabusMetadataFields from '../components/SyllabusMetadataFields.vue'
import SyllabusSectionDialog from '../components/SyllabusSectionDialog.vue'
const route = useRoute(),
  api = createSyllabusApi(useAuthStore())
const detail = ref<SyllabusDetail | null>(null),
  loading = ref(false),
  busy = ref(false),
  downloading = ref(false)
const metadata = ref<SyllabusMetadata>({
  title: '',
  versionLabel: '',
  issuer: '',
  publishedDate: null,
  sourceUrl: '',
})
const sections = ref<SyllabusSection[]>([]),
  selected = ref<SyllabusSection | null>(null),
  pagesVisible = ref(false),
  selectedPage = ref<number>()
const metadataForm = ref<FormInstance>()
let generation = 0,
  viewGeneration = 0,
  alive = true,
  active = true,
  pollTimer: ReturnType<typeof setTimeout> | undefined
const currentId = ref(''),
  editRevision = ref(0),
  pollFailed = ref(false)
const reviewStatus = computed(() => detail.value?.reviewStatus ?? 'not_started')
const indexStatus = computed(() => detail.value?.indexStatus ?? 'not_started')
const reviewActive = computed(() => syllabusJobActive(reviewStatus.value))
const taskActive = computed(() => reviewActive.value || syllabusJobActive(indexStatus.value))
const editable = computed(() => detail.value?.status === 'draft' && !reviewActive.value)
const payload = computed<SyllabusUpdate>(() => ({
  ...metadata.value,
  revision: editRevision.value,
  sections: sections.value,
}))
const baseline = ref('')
const dirty = computed(() => JSON.stringify(payload.value) !== baseline.value)
function validEnabled(section: SyllabusSection) {
  return (
    ['overview', 'requirement'].includes(section.kind) &&
    !!section.text.trim() &&
    section.scope ===
      (detail.value?.examType === 'career_a' ? 'vocational_aptitude' : 'administrative_aptitude')
  )
}
const canPublish = computed(
  () =>
    editable.value &&
    (reviewStatus.value === 'ready' || detail.value?.reviewStatus === undefined) &&
    !dirty.value &&
    sections.value.some((s) => s.enabled) &&
    sections.value.every((s) => !s.enabled || validEnabled(s)),
)
const page = computed(() => detail.value?.pages.find((p) => p.page === selectedPage.value))
function accept(value: SyllabusDetail, preserveEdits = false) {
  if (detail.value && value.revision < detail.value.revision) return
  const preserve = preserveEdits && (dirty.value || !!selected.value)
  detail.value = value
  if (preserve) return
  editRevision.value = value.revision
  metadata.value = {
    title: value.title,
    versionLabel: value.versionLabel,
    issuer: value.issuer,
    publishedDate: value.publishedDate,
    sourceUrl: value.sourceUrl ?? '',
  }
  sections.value = value.sections.map((s) => ({ ...s }))
  baseline.value = JSON.stringify(payload.value)
  selectedPage.value = value.pages[0]?.page
}
function clearPoll() {
  if (pollTimer !== undefined) clearTimeout(pollTimer)
  pollTimer = undefined
}
function isCurrent(version: number) {
  return alive && active && version === generation && route.name === 'AdminSyllabusDetailView'
}
function schedulePoll() {
  clearPoll()
  if (!isCurrent(generation) || !taskActive.value || busy.value || pollFailed.value) return
  pollTimer = setTimeout(() => void refreshStatus(), 3000)
}
async function refreshStatus() {
  clearPoll()
  if (!isCurrent(generation) || busy.value || loading.value || !detail.value) return
  const version = ++generation,
    id = currentId.value
  pollFailed.value = false
  try {
    const result = await api.detail(id)
    if (isCurrent(version)) {
      accept(result, true)
      schedulePoll()
    }
  } catch (e) {
    if (isCurrent(version)) {
      pollFailed.value = true
      notifyError(e)
    }
  }
}
async function load(id: string) {
  clearPoll()
  const version = ++generation
  currentId.value = id
  detail.value = null
  selected.value = null
  pagesVisible.value = false
  loading.value = true
  busy.value = false
  pollFailed.value = false
  try {
    const result = await api.detail(id)
    if (isCurrent(version)) accept(result)
  } catch (e) {
    if (isCurrent(version)) notifyError(e)
  } finally {
    if (isCurrent(version)) {
      loading.value = false
      schedulePoll()
    }
  }
}
watch(
  () => (route.name === 'AdminSyllabusDetailView' ? String(route.params.id ?? '') : ''),
  (id) => {
    clearPoll()
    generation++
    viewGeneration++
    if (!id || !active) return
    if (id !== currentId.value || !detail.value) void load(id)
    else {
      loading.value = false
      busy.value = false
      schedulePoll()
    }
  },
  { immediate: true },
)
async function save() {
  if (
    !editable.value ||
    busy.value ||
    loading.value ||
    !metadataForm.value ||
    (await metadataForm.value.validate())
  )
    return
  await mutate('save')
}
async function mutate(action: 'save' | 'publish' | 'archive' | 'retryReview' | 'rebuildIndex') {
  if (!isCurrent(generation) || !detail.value || busy.value || loading.value || selected.value)
    return
  if (action === 'publish' && !canPublish.value) return
  if (
    action === 'archive' &&
    (dirty.value || reviewActive.value || detail.value.status === 'archived')
  )
    return
  if (action === 'save' && !editable.value) return
  if (
    action === 'retryReview' &&
    (detail.value.status !== 'draft' || reviewStatus.value !== 'failed' || dirty.value)
  )
    return
  if (
    action === 'rebuildIndex' &&
    (!['published', 'archived'].includes(detail.value.status) ||
      syllabusJobActive(indexStatus.value) ||
      dirty.value)
  )
    return
  clearPoll()
  const version = ++generation,
    id = currentId.value,
    revision = detail.value.revision
  busy.value = true
  try {
    const result =
      action === 'save'
        ? await api.update(id, {
            ...payload.value,
            sections: sections.value.map((s) => ({ ...s })),
          })
        : action === 'publish'
          ? await api.publish(id, revision)
          : action === 'archive'
            ? await api.archive(id, revision)
            : action === 'retryReview'
              ? await api.retryReview(id, revision)
              : await api.rebuildIndex(id, revision)
    if (isCurrent(version)) {
      accept(result)
      pollFailed.value = false
      Message.success(
        {
          save: '草稿已保存',
          publish: '考纲已发布，索引状态请查看下方任务',
          archive: '考纲已归档，历史引用保留',
          retryReview: 'AI 整理任务已提交',
          rebuildIndex: '索引重建任务已提交',
        }[action],
      )
    }
  } catch (e) {
    if (isCurrent(version)) notifyError(e)
  } finally {
    if (isCurrent(version)) {
      busy.value = false
      schedulePoll()
    }
  }
}
function applySection(section: SyllabusSection) {
  if (!editable.value || busy.value || !selected.value) return
  const index = sections.value.findIndex((s) => s.ordinal === selected.value?.ordinal)
  if (index >= 0) sections.value[index] = section
  selected.value = null
}
async function download() {
  if (downloading.value || !detail.value) return
  const version = viewGeneration,
    name = detail.value.sourceName,
    id = currentId.value
  downloading.value = true
  try {
    const blob = await api.file(id)
    if (!alive || !active || version !== viewGeneration) return
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = name
    document.body.append(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    if (alive && active && version === viewGeneration) notifyError(e)
  } finally {
    downloading.value = false
  }
}
onDeactivated(() => {
  active = false
  generation++
  viewGeneration++
  clearPoll()
})
onActivated(() => {
  if (active) return
  active = true
  const id = route.name === 'AdminSyllabusDetailView' ? String(route.params.id ?? '') : ''
  if (!id) return
  loading.value = false
  busy.value = false
  if (id !== currentId.value || !detail.value) void load(id)
  else schedulePoll()
})
onBeforeUnmount(() => {
  alive = false
  generation++
  clearPoll()
})
const kindLabels = { overview: '概述', requirement: '要求', example: '例题', other: '其他' }
</script>
<template>
  <a-card title="考纲详情" class="admin-users-card" :bordered="false">
    <a-spin :loading="loading" style="width: 100%">
      <template v-if="detail">
        <div class="user-toolbar">
          <a-space
            ><strong>{{ detail.title }}</strong
            ><a-tag>{{ SYLLABUS_STATUSES[detail.status] }}</a-tag
            ><span>修订 {{ detail.revision }}</span></a-space
          ><a-space
            ><a-button data-test="view-pages" :disabled="busy" @click="pagesVisible = true"
              >查看页文本</a-button
            ><a-button data-test="download-syllabus" :loading="downloading" @click="download"
              >下载原件</a-button
            ></a-space
          >
        </div>
        <a-descriptions :column="2" bordered
          ><a-descriptions-item label="考试">{{
            SYLLABUS_EXAMS.find((item) => item.value === detail?.examType)?.label
          }}</a-descriptions-item
          ><a-descriptions-item label="考试年份">{{ detail.examYear }}</a-descriptions-item
          ><a-descriptions-item label="原件"
            >{{ detail.sourceName }}（{{ detail.pageCount }}页）</a-descriptions-item
          ><a-descriptions-item label="SHA256">{{
            detail.sha256
          }}</a-descriptions-item></a-descriptions
        >
        <section class="syllabus-jobs" aria-label="考纲处理任务">
          <div class="user-toolbar">
            <a-space>
              <strong>AI 整理</strong>
              <a-tag
                :color="
                  reviewStatus === 'failed' ? 'red' : reviewStatus === 'ready' ? 'green' : 'gray'
                "
                >{{ SYLLABUS_REVIEW_STATUSES[reviewStatus] }}</a-tag
              >
            </a-space>
            <a-button
              v-if="detail.status === 'draft' && reviewStatus === 'failed'"
              data-test="retry-review"
              :loading="busy"
              :disabled="busy || dirty || !!selected"
              @click="mutate('retryReview')"
              >重试AI整理</a-button
            >
          </div>
          <a-alert v-if="reviewActive" type="info"
            >AI 正在整理章节建议，完成前不能编辑或发布；完成后仍须逐章审核启用。</a-alert
          >
          <a-alert v-if="reviewStatus === 'failed'" type="error">{{
            detail.reviewError || 'AI 整理失败，请重试。'
          }}</a-alert>
          <div class="user-toolbar">
            <a-space>
              <strong>向量索引</strong>
              <a-tag
                :color="
                  indexStatus === 'failed' ? 'red' : indexStatus === 'ready' ? 'green' : 'gray'
                "
                >{{ SYLLABUS_INDEX_STATUSES[indexStatus] }}</a-tag
              >
              <span data-test="index-progress"
                >已索引 {{ detail.indexedCount ?? 0 }} / {{ detail.chunkCount ?? 0 }} 个切片</span
              >
            </a-space>
            <a-button
              v-if="
                ['published', 'archived'].includes(detail.status) && !syllabusJobActive(indexStatus)
              "
              data-test="rebuild-index"
              :loading="busy"
              :disabled="busy || dirty || !!selected"
              @click="mutate('rebuildIndex')"
              >重建索引</a-button
            >
          </div>
          <div class="syllabus-model">
            Embedding 模型：{{ detail.embeddingModel || '—' }} · 维度：{{
              detail.embeddingDimensions ?? '—'
            }}
          </div>
          <a-alert v-if="indexStatus === 'failed'" type="error">{{
            detail.indexError || '索引失败，请重建索引。'
          }}</a-alert>
          <a-alert v-if="pollFailed" type="warning"
            >状态刷新失败，已暂停自动刷新。<template #action
              ><a-button
                data-test="refresh-syllabus-status"
                type="text"
                :disabled="busy"
                @click="refreshStatus"
                >刷新状态</a-button
              ></template
            ></a-alert
          >
        </section>
        <a-alert v-if="detail.status !== 'draft'" type="info">{{
          detail.status === 'published'
            ? '已发布版本不可编辑；修改请上传新版本。'
            : '已归档，不再供新会话检索，历史引用保留。'
        }}</a-alert>
        <a-alert v-if="detail.warnings?.length" type="warning" title="需要你核对的疑点">
          <ol class="review-issues">
            <li v-for="(warning, index) in detail.warnings" :key="index">{{ warning }}</li>
          </ol>
        </a-alert>
        <p v-if="detail.technicalWarningCount" class="technical-note">
          另有 {{ detail.technicalWarningCount }} 条解析器技术提示（逐页含图、
          运行环境限制等），不影响内容，可忽略。
        </p>
        <a-form
          ref="metadataForm"
          :model="metadata"
          auto-label-width
          :disabled="!editable || busy || loading"
          @submit-success="save"
          ><SyllabusMetadataFields v-model:model="metadata" prefix="detail"
        /></a-form>
        <div class="user-toolbar">
          <a-space
            ><strong>章节审核</strong
            ><span
              >启用 {{ sections.filter((s) => s.enabled).length }} / {{ sections.length }}</span
            ></a-space
          ><span v-if="dirty">有未保存修改，请先保存草稿</span>
        </div>
        <a-table row-key="ordinal" :data="sections" :pagination="false" :scroll="{ x: 1100 }"
          ><template #columns>
            <a-table-column title="序号" data-index="ordinal" :width="70" />
            <a-table-column title="章节" :width="230"
              ><template #cell="{ record }"
                ><strong>{{ record.title }}</strong>
                <div>{{ record.path }}</div></template
              ></a-table-column
            >
            <a-table-column title="类型" :width="80"
              ><template #cell="{ record }">{{
                kindLabels[record.kind as keyof typeof kindLabels]
              }}</template></a-table-column
            >
            <a-table-column title="范围" :width="170"
              ><template #cell="{ record }">{{
                record.scope === 'excluded' ? '排除（仅保留原文）' : subjectLabel(record.scope)
              }}</template></a-table-column
            >
            <a-table-column title="物理页 / 印刷页" :width="150"
              ><template #cell="{ record }"
                >{{ record.pageStart }}–{{ record.pageEnd }} /
                {{ record.printedPages || '—' }}</template
              ></a-table-column
            >
            <a-table-column title="启用状态" :width="100"
              ><template #cell="{ record }"
                ><a-tag :color="record.enabled ? 'green' : 'gray'">{{
                  record.enabled ? '已启用' : '未启用'
                }}</a-tag></template
              ></a-table-column
            >
            <a-table-column title="操作" :width="90"
              ><template #cell="{ record }"
                ><a-button
                  v-if="editable"
                  :data-test="`edit-section-${record.ordinal}`"
                  type="text"
                  size="small"
                  :disabled="busy"
                  @click="selected = { ...record }"
                  >编辑</a-button
                ><a-button v-else type="text" size="small" @click="selected = { ...record }"
                  >查看原文</a-button
                ></template
              ></a-table-column
            >
          </template></a-table
        >
        <a-space class="syllabus-actions">
          <a-button
            v-if="editable"
            data-test="save-syllabus"
            type="primary"
            :loading="busy"
            :disabled="!dirty || !!selected"
            @click="save"
            >保存草稿</a-button
          >
          <a-popconfirm
            v-if="editable"
            content="确认已逐章审核范围与启用状态？发布后不可编辑。"
            @ok="mutate('publish')"
            ><a-button data-test="publish-syllabus" :disabled="busy || !canPublish || !!selected"
              >发布</a-button
            ></a-popconfirm
          >
          <a-popconfirm
            v-if="detail.status !== 'archived'"
            content="归档后不再供新会话使用，历史引用及原件保留。"
            @ok="mutate('archive')"
            ><a-button
              data-test="archive-syllabus"
              status="warning"
              :disabled="busy || reviewActive || dirty || !!selected"
              >归档</a-button
            ></a-popconfirm
          >
        </a-space> </template
      ><a-empty v-else-if="!loading" description="考纲详情加载失败" />
    </a-spin>
  </a-card>
  <SyllabusSectionDialog
    v-if="detail && editable"
    :section="selected"
    :exam-type="detail.examType"
    :page-count="detail.pageCount"
    :ordinals="sections.filter((s) => s.ordinal !== selected?.ordinal).map((s) => s.ordinal)"
    @close="selected = null"
    @apply="applySection"
  />
  <a-modal
    :visible="!!selected && !editable"
    title="章节原文"
    title-align="start"
    :width="760"
    modal-class="user-dialog"
    @cancel="selected = null"
  >
    <pre class="syllabus-text">{{ selected?.text }}</pre>
    <template #footer><a-button @click="selected = null">关闭</a-button></template></a-modal
  >
  <a-modal
    v-model:visible="pagesVisible"
    title="原件页文本"
    title-align="start"
    :width="800"
    modal-class="user-dialog"
    ><a-select v-model="selectedPage" aria-label="物理页码"
      ><a-option v-for="item in detail?.pages" :key="item.page" :value="item.page"
        >物理页 {{ item.page }} · 印刷页 {{ item.printedPage ?? '—' }}</a-option
      ></a-select
    >
    <pre data-test="page-text" class="syllabus-text">{{ page?.text }}</pre>
    <template #footer
      ><a-button data-test="close-pages" @click="pagesVisible = false">关闭</a-button></template
    ></a-modal
  >
</template>
<style scoped>
.syllabus-actions {
  margin-top: 20px;
}
.syllabus-jobs {
  margin: 16px 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border-2);
}
.syllabus-model {
  margin-bottom: 12px;
  color: var(--color-text-3);
  overflow-wrap: anywhere;
}
.syllabus-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.review-issues {
  margin: 8px 0 0;
  padding-left: 20px;
  line-height: 1.9;
}
.technical-note {
  margin: 4px 0 16px;
  font-size: 12px;
  color: var(--color-text-3);
}
</style>
