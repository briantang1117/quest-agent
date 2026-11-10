<script setup lang="ts">
import { onActivated, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, type FileItem, type FormInstance } from '@arco-design/web-vue'
import { IconSearch, IconRefresh, IconUpload } from '@arco-design/web-vue/es/icon'
import {
  createSyllabusApi,
  notifyError,
  SYLLABUS_EXAMS,
  SYLLABUS_STATUSES,
  SYLLABUS_REVIEW_STATUSES,
  SYLLABUS_INDEX_STATUSES,
  useAuthStore,
  type SyllabusJobStatus,
  type SyllabusFilters,
  type SyllabusMetadata,
  type SyllabusPage,
  type SyllabusRow,
  type SyllabusExamType,
} from '@quest/shared'
import SyllabusMetadataFields from '../components/SyllabusMetadataFields.vue'
const api = createSyllabusApi(useAuthStore())
const router = useRouter(),
  route = useRoute()
const emptyFilters = () => ({
  keyword: '',
  examType: '' as SyllabusFilters['examType'],
  examYear: undefined as number | undefined,
  status: '' as SyllabusFilters['status'],
})
const filters = reactive(emptyFilters())
const query = reactive<SyllabusFilters>({ page: 1, size: 20, ...emptyFilters() })
const data = ref<SyllabusPage | null>(null),
  loading = ref(false),
  failed = ref(false),
  saving = ref(false),
  visible = ref(false)
const metadata = ref<SyllabusMetadata>({
  title: '',
  versionLabel: '',
  issuer: '',
  publishedDate: null,
  sourceUrl: '',
})
const uploadModel = reactive({
  examType: undefined as SyllabusExamType | undefined,
  examYear: undefined as number | undefined,
})
const uploadForm = ref<FormInstance>(),
  files = ref<FileItem[]>([]),
  uploadError = ref('')
let alive = true,
  generation = 0,
  mounted = false
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
function date(value: string) {
  return Number.isFinite(Date.parse(value)) ? dateFormat.format(Date.parse(value)) : '时间不可用'
}
async function load() {
  const version = ++generation
  loading.value = true
  failed.value = false
  try {
    const result = await api.list({ ...query })
    if (alive && generation === version) data.value = result
  } catch (e) {
    if (alive && generation === version) {
      failed.value = true
      notifyError(e)
    }
  } finally {
    if (alive && generation === version) loading.value = false
  }
}
const retrying = ref('')
async function retryReview(record: SyllabusRow) {
  if (retrying.value) return
  retrying.value = record.id
  try {
    await api.retryReview(record.id, record.revision)
    Message.success('已重新提交 AI 整理')
    await load()
  } catch (e) {
    notifyError(e)
  } finally {
    retrying.value = ''
  }
}
async function rebuildIndex(record: SyllabusRow) {
  if (retrying.value) return
  retrying.value = record.id
  try {
    await api.rebuildIndex(record.id, record.revision)
    Message.success('已提交索引重建')
    await load()
  } catch (e) {
    notifyError(e)
  } finally {
    retrying.value = ''
  }
}
function filter() {
  Object.assign(query, filters, { keyword: filters.keyword.trim(), page: 1 })
  void load()
}
function reset() {
  Object.assign(filters, emptyFilters())
  filter()
}
function changePage(page: number) {
  query.page = page
  void load()
}
function changeSize(size: number) {
  query.size = size
  query.page = 1
  void load()
}
function open() {
  if (saving.value) return
  metadata.value = { title: '', versionLabel: '', issuer: '', publishedDate: null, sourceUrl: '' }
  Object.assign(uploadModel, { examType: undefined, examYear: undefined })
  files.value = []
  uploadError.value = ''
  visible.value = true
}
function close() {
  if (!saving.value) visible.value = false
}
async function submit() {
  if (saving.value || !uploadForm.value) return
  if (await uploadForm.value.validate()) return
  if (saving.value) return
  const file = files.value[0]?.file
  if (
    !file ||
    !/\.pdf$/i.test(file.name) ||
    (file.type && file.type !== 'application/pdf') ||
    file.size === 0 ||
    file.size > 20 * 1024 * 1024
  ) {
    uploadError.value = '请选择不超过20 MiB的PDF原件'
    return
  }
  const { examType, examYear } = uploadModel
  if (!examType || !Number.isInteger(examYear) || examYear! < 2000 || examYear! > 2100) {
    uploadError.value = '请明确填写2000至2100的考试年份及考试类型'
    return
  }
  saving.value = true
  let uploadedId: string | undefined
  try {
    const result = await api.upload(file, { ...metadata.value, examType, examYear: examYear! })
    if (!alive) return
    visible.value = false
    uploadedId = result.id
    Message.success('考纲已上传，AI 整理任务将自动处理，请在详情查看状态')
  } catch (e) {
    if (alive) notifyError(e)
  } finally {
    saving.value = false
  }
  // 上传已成功后，导航失败也不能重新打开弹窗诱导重复上传。
  if (uploadedId && alive) {
    try {
      await router.push(`/admin/syllabi/${uploadedId}`)
    } catch (e) {
      notifyError(e)
    }
  }
}
onMounted(() => {
  mounted = true
  void load()
})
onActivated(() => {
  if (mounted && route.name === 'AdminSyllabiView' && !loading.value) void load()
})
onBeforeUnmount(() => {
  alive = false
  generation++
})
</script>
<template>
  <a-card class="admin-users-card" title="考纲管理" :bordered="false">
    <a-form
      data-test="syllabus-filter"
      :model="filters"
      auto-label-width
      :disabled="loading || saving"
      @submit-success="filter"
    >
      <div class="user-filter-area">
        <div class="user-filter-form">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="标题"
                ><a-input
                  v-model="filters.keyword"
                  :input-attrs="{ id: 'syllabus-keyword' }"
                  placeholder="请输入标题"
                  allow-clear /></a-form-item
            ></a-col>
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="考试"
                ><a-select v-model="filters.examType"
                  ><a-option value="">全部</a-option
                  ><a-option v-for="item in SYLLABUS_EXAMS" :key="item.value" :value="item.value">{{
                    item.label
                  }}</a-option></a-select
                ></a-form-item
              ></a-col
            >
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="考试年份"
                ><a-input-number
                  v-model="filters.examYear"
                  :min="2000"
                  :max="2100"
                  :precision="0"
                  placeholder="全部年份" /></a-form-item
            ></a-col>
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="状态"
                ><a-select v-model="filters.status"
                  ><a-option value="">全部</a-option
                  ><a-option
                    v-for="(label, value) in SYLLABUS_STATUSES"
                    :key="value"
                    :value="value"
                    >{{ label }}</a-option
                  ></a-select
                ></a-form-item
              ></a-col
            >
          </a-row>
        </div>
        <div class="user-filter-actions">
          <a-button type="primary" html-type="submit" :disabled="loading || saving"
            ><template #icon><IconSearch /></template>查询</a-button
          ><a-button data-test="reset-syllabi" :disabled="loading || saving" @click="reset"
            ><template #icon><IconRefresh /></template>清空</a-button
          >
        </div>
      </div>
    </a-form>
    <a-divider />
    <div class="user-toolbar">
      <a-button
        data-test="upload-syllabus"
        type="primary"
        :disabled="loading || saving"
        @click="open"
        ><template #icon><IconUpload /></template>上传考纲</a-button
      ><a-button :loading="loading" :disabled="saving" @click="load"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <a-alert v-if="failed" type="error">考纲列表加载失败，请刷新重试。</a-alert>
    <a-table
      v-else
      row-key="id"
      :data="data?.list ?? []"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1640 }"
      ><template #columns>
        <a-table-column title="标题" :fixed="'left'" data-index="title" :width="220" />
        <a-table-column title="考试" :width="160"
          ><template #cell="{ record }">{{
            SYLLABUS_EXAMS.find((item) => item.value === record.examType)?.label
          }}</template></a-table-column
        >
        <a-table-column title="年份" data-index="examYear" :width="80" /><a-table-column
          title="版本"
          data-index="versionLabel"
          :width="120"
        />
        <a-table-column title="状态" :width="100"
          ><template #cell="{ record }"
            ><a-tag :color="record.status === 'published' ? 'green' : 'gray'">{{
              SYLLABUS_STATUSES[record.status as keyof typeof SYLLABUS_STATUSES]
            }}</a-tag></template
          ></a-table-column
        >
        <a-table-column title="AI 整理" :width="160">
          <template #cell="{ record }">
            <a-tag
              :color="
                record.reviewStatus === 'failed'
                  ? 'red'
                  : record.reviewStatus === 'ready'
                    ? 'green'
                    : 'gray'
              "
              >{{
                SYLLABUS_REVIEW_STATUSES[
                  (record.reviewStatus ?? 'not_started') as SyllabusJobStatus
                ]
              }}</a-tag
            >
            <div v-if="record.reviewStatus === 'failed'">
              {{ record.reviewError || 'AI 整理失败，请在详情重试。' }}
            </div>
          </template>
        </a-table-column>
        <a-table-column title="向量索引" :width="280">
          <template #cell="{ record }">
            <a-tag
              :color="
                record.indexStatus === 'failed'
                  ? 'red'
                  : record.indexStatus === 'ready'
                    ? 'green'
                    : 'gray'
              "
              >{{
                SYLLABUS_INDEX_STATUSES[(record.indexStatus ?? 'not_started') as SyllabusJobStatus]
              }}</a-tag
            >
            <div>切片 {{ record.indexedCount ?? 0 }} / {{ record.chunkCount ?? 0 }}</div>
            <div>
              模型：{{ record.embeddingModel || '—' }} · 维度：{{
                record.embeddingDimensions ?? '—'
              }}
            </div>
            <div v-if="record.indexStatus === 'failed'">
              {{ record.indexError || '索引失败，请在详情重建。' }}
            </div>
          </template>
        </a-table-column>
        <a-table-column title="章节（启用 / 总数）" :width="150"
          ><template #cell="{ record }"
            >{{ record.enabledCount }} / {{ record.sectionCount }}</template
          ></a-table-column
        >
        <a-table-column title="页数" data-index="pageCount" :width="70" /><a-table-column
          title="创建时间"
          :width="170"
          ><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column
        >
        <a-table-column title="操作" :width="140" fixed="right"
          ><template #cell="{ record }"
            ><a-space :wrap="true"
              ><a-button
                data-test="syllabus-detail"
                type="text"
                size="small"
                @click="router.push(`/admin/syllabi/${record.id}`)"
                >查看详情</a-button
              ><a-button
                v-if="record.reviewStatus === 'failed'"
                data-test="list-retry-review"
                type="text"
                size="small"
                :loading="retrying === record.id"
                @click="retryReview(record)"
                >重试整理</a-button
              ><a-button
                v-if="
                  record.indexStatus === 'failed' &&
                  (record.status === 'published' || record.status === 'archived')
                "
                data-test="list-rebuild-index"
                type="text"
                size="small"
                :loading="retrying === record.id"
                @click="rebuildIndex(record)"
                >重建索引</a-button
              ></a-space
            ></template
          ></a-table-column
        > </template
      ><template #empty><a-empty description="没有符合筛选条件的考纲" /></template
    ></a-table>
    <div v-if="data && !failed" class="user-pagination">
      <span>共 {{ data.total }} 个匹配考纲</span
      ><a-pagination
        :total="data.total"
        :current="query.page"
        :page-size="query.size"
        :page-size-options="[20, 50, 100]"
        show-page-size
        @change="changePage"
        @page-size-change="changeSize"
      />
    </div>
  </a-card>
  <a-modal
    :visible="visible"
    title="上传考纲 PDF"
    title-align="start"
    modal-class="user-dialog"
    :width="620"
    :mask-closable="false"
    :closable="!saving"
    :esc-to-close="!saving"
    unmount-on-close
    @cancel="close"
  >
    <a-form
      ref="uploadForm"
      :model="{ ...metadata, ...uploadModel }"
      auto-label-width
      :disabled="saving"
      @submit-success="submit"
    >
      <a-form-item label="PDF原件"
        ><a-upload
          v-model:file-list="files"
          :auto-upload="false"
          :limit="1"
          accept=".pdf,application/pdf"
          draggable
          :disabled="saving"
          tip="PDF ≤20 MiB / 200页；上传后自动 AI 整理，不自动启用或发布。"
      /></a-form-item>
      <SyllabusMetadataFields v-model:model="metadata" prefix="upload" />
      <a-form-item
        field="examType"
        label="考试"
        :rules="[{ required: true, message: '请选择考试' }]"
        ><a-select v-model="uploadModel.examType" placeholder="请选择考试"
          ><a-option v-for="item in SYLLABUS_EXAMS" :key="item.value" :value="item.value">{{
            item.label
          }}</a-option></a-select
        ></a-form-item
      >
      <a-form-item
        field="examYear"
        label="考试年份"
        :rules="[{ required: true, message: '请明确填写考试年份' }]"
        ><a-input-number
          v-model="uploadModel.examYear"
          :min="2000"
          :max="2100"
          :precision="0"
          placeholder="2000–2100"
      /></a-form-item> </a-form
    ><a-alert v-if="uploadError" type="error">{{ uploadError }}</a-alert>
    <template #footer
      ><a-button :disabled="saving" @click="close">取消</a-button
      ><a-button data-test="confirm-upload" type="primary" :loading="saving" @click="submit"
        >上传并AI整理</a-button
      ></template
    >
  </a-modal>
</template>
