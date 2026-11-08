<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, type FileItem } from '@arco-design/web-vue'
import { IconDown, IconRefresh, IconSearch } from '@arco-design/web-vue/es/icon'
import {
  createPublicBankApi,
  notifyError,
  useAuthStore,
  type PublicBank,
  type ImportTask,
  type ImportDocument,
  type PublicQuestionRow,
  type PublicQuestionSummary,
} from '@quest/shared'
const api = createPublicBankApi(useAuthStore()),
  route = useRoute(),
  router = useRouter()
const bank = ref<PublicBank | null>(null)
const summary = ref<PublicQuestionSummary | null>(null)
const documentVisible = ref(false)
const filters = reactive({ keyword: '', status: '' as PublicQuestionRow['status'] | '' })
const questionQuery = reactive({
  size: 20,
  keyword: '',
  status: '' as PublicQuestionRow['status'] | '',
})
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
const bankCreatedAt = computed(() => {
  const time = Date.parse(bank.value?.createdAt ?? '')
  return Number.isFinite(time) ? dateFormat.format(time) : '时间不可用'
})
const bankSubject = computed(() => {
  if (bank.value?.subject === 'vocational_aptitude') return '职测'
  if (bank.value?.subject === 'administrative_aptitude') return '行测'
  return bank.value?.subject || (bank.value?.examType === 'career_a' ? '职测' : '行测')
})
const questions = ref<PublicQuestionRow[]>([]),
  documents = ref<ImportDocument[]>([])
const questionPage = ref(1),
  questionTotal = ref(0),
  documentPage = ref(1),
  documentTotal = ref(0)
const selectedQuestions = ref<(string | number)[]>([])
const selectedVersions = new Map<string, number>()
const questionRows = computed(() =>
  questions.value.map((question) => ({
    ...question,
    disabled: busy.value || question.status !== 'confirmed',
  })),
)
function selectQuestions(keys: (string | number)[]) {
  if (busy.value) return
  selectedQuestions.value = keys.filter((key) =>
    questions.value.some((question) => question.id === key && question.status === 'confirmed'),
  )
  selectedVersions.clear()
  for (const id of selectedQuestions.value)
    selectedVersions.set(
      String(id),
      questions.value.find((question) => question.id === id)!.version,
    )
}
function clearSelection() {
  selectedQuestions.value = []
  selectedVersions.clear()
}
async function publishSelected() {
  if (busy.value || !bank.value || !selectedQuestions.value.length) return
  const current = revision,
    id = bank.value.id
  const items = selectedQuestions.value.map((key) => ({
    id: String(key),
    version: selectedVersions.get(String(key))!,
  }))
  busy.value = true
  try {
    const result = await api.publishBatch(id, items)
    if (alive && current === revision) {
      clearSelection()
      await loadContent()
      Message.success(`已发布 ${result.publishedCount} 道题目`)
    }
  } catch (e) {
    if (alive && current === revision) {
      notifyError(e)
      clearSelection()
      await loadContent()
    }
  } finally {
    if (alive && current === revision) busy.value = false
  }
}
const pdfVisible = ref(false)
const pdfFiles = ref<FileItem[]>([])
const pdfFile = computed(() => pdfFiles.value[0]?.file ?? null)
let contentRevision = 0
const contentLoading = ref(false)
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  importVisible = ref(false)
const text = ref('')
const singleTask = ref<ImportTask | null>(null)
const singleStatus = ref('')
const singlePaused = ref(false)
const singlePending = computed(
  () => singleTask.value?.status === 'queued' || singleTask.value?.status === 'processing',
)
const importBusy = computed(() => busy.value || (singlePending.value && !singlePaused.value))
let singleTimer: ReturnType<typeof setTimeout> | undefined
let singleChecking = false
let singlePolls = 0
function stopSinglePolling() {
  clearTimeout(singleTimer)
  singleTimer = undefined
}
function scheduleSinglePolling() {
  stopSinglePolling()
  if (alive && active.value && singlePending.value && !singlePaused.value && !singleChecking) {
    singleTimer = setTimeout(() => void checkSingleTask(), 3000)
  }
}
async function acceptSingleTask(task: ImportTask) {
  singleTask.value = task
  if (task.status === 'reviewing' && task.questionId) {
    singleStatus.value = '解析完成，题目未确认'
    importVisible.value = false
    text.value = ''
    questionPage.value = 1
    if (active.value) await loadContent()
    Message.success('解析完成，题目尚未确认')
  } else if (task.status === 'failed') {
    singleStatus.value = '解析未完成，请检查输入后重新提交'
    notifyError(new Error('single_import_failed'), '单题解析未完成，请检查输入后重试')
  } else singleStatus.value = '解析中，请稍候'
}
async function checkSingleTask() {
  if (!alive || !active.value || !singleTask.value || singleChecking) return false
  const current = revision,
    id = singleTask.value.id
  singleChecking = true
  singlePaused.value = false
  try {
    const result = await api.importTask(id)
    if (alive && current === revision && singleTask.value?.id === id) {
      await acceptSingleTask(result)
      if (++singlePolls >= 40 && singlePending.value) {
        singlePaused.value = true
        singleStatus.value = '解析仍在进行，可稍后查询进度；不会重复提交'
      }
    }
  } catch (e) {
    if (alive && current === revision) {
      singlePaused.value = true
      singleStatus.value = '暂时无法获取解析进度，可稍后查询；不会重复提交'
      notifyError(e)
    }
  } finally {
    if (alive && current === revision) {
      singleChecking = false
      scheduleSinglePolling()
    }
  }
  return false
}
function openManual(value: unknown) {
  if (busy.value || value !== 'manual') return
  importVisible.value = true
}
let alive = true,
  revision = 0
const visible = ref(true)
const active = computed(
  () => visible.value && route.name === 'AdminBankDetailView' && route.params.id === bank.value?.id,
)
const hasPending = computed(() =>
  documents.value.some((document) => document.queuedCount > 0 || document.processingCount > 0),
)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let refreshing: { identity: number; promise: Promise<void> } | undefined
function stopPolling() {
  clearTimeout(pollTimer)
  pollTimer = undefined
}
function schedulePolling() {
  stopPolling()
  if (alive && active.value && hasPending.value && refreshing?.identity !== revision) {
    pollTimer = setTimeout(() => {
      pollTimer = undefined
      void refreshLists()
    }, 3000)
  }
}
function refreshLists() {
  if (!active.value || !alive) return Promise.resolve()
  if (refreshing?.identity === revision) return refreshing.promise
  stopPolling()
  const pending = {
    identity: revision,
    promise: loadContent()
      .then(() => {})
      .finally(() => {
        if (refreshing === pending) {
          refreshing = undefined
          schedulePolling()
        }
      }),
  }
  refreshing = pending
  return pending.promise
}
onActivated(() => {
  const returning = !visible.value
  visible.value = true
  if (returning) void refreshLists()
})
onDeactivated(() => {
  visible.value = false
  stopPolling()
  stopSinglePolling()
})
onBeforeUnmount(() => {
  alive = false
  stopPolling()
  stopSinglePolling()
  revision++
  contentRevision++
})
watch([active, hasPending], schedulePolling)
watch([active, singlePending], scheduleSinglePolling)
function documentStatus(document: ImportDocument) {
  if (document.status === 'staged') return '待核对并启动'
  if (document.status === 'needs_attention') return '需要视觉/人工分段'
  if (document.processingCount > 0) return '处理中'
  if (document.queuedCount > 0) return '待处理'
  if (document.failedCount > 0)
    return document.failedCount === document.totalCount ? '失败' : '部分失败'
  if (document.reviewingCount > 0) return '未确认'
  if (document.publishedCount > 0)
    return document.publishedCount === document.totalCount - (document.deletedCount ?? 0)
      ? '已发布'
      : '部分发布'
  if (document.confirmedCount > 0) return '已确认'
  return document.deletedCount > 0 ? '已删除' : '暂无任务'
}
async function load() {
  const current = ++revision
  stopPolling()
  contentRevision++
  questions.value = []
  clearSelection()
  documents.value = []
  questionPage.value = 1
  questionQuery.size = 20
  questionQuery.keyword = ''
  questionQuery.status = ''
  filters.keyword = ''
  filters.status = ''
  summary.value = null
  documentVisible.value = false
  documentPage.value = 1
  questionTotal.value = 0
  documentTotal.value = 0
  bank.value = null
  error.value = ''
  importVisible.value = false
  pdfVisible.value = false
  pdfFiles.value = []
  text.value = ''
  singleTask.value = null
  singleStatus.value = ''
  singlePaused.value = false
  singleChecking = false
  singlePolls = 0
  stopSinglePolling()
  busy.value = false
  loading.value = true
  try {
    const result = await api.detail(String(route.params.id))
    if (alive && current === revision) {
      bank.value = result
      await loadContent()
    }
  } catch (e) {
    if (alive && current === revision) {
      error.value = '加载失败'
      notifyError(e)
    }
  } finally {
    if (alive && current === revision) loading.value = false
  }
}
async function loadContent() {
  if (!bank.value || !active.value || !alive) return
  const current = ++contentRevision,
    identity = revision,
    id = bank.value.id
  contentLoading.value = true
  try {
    const [q, d, counts] = await Promise.all([
      api.questions(
        id,
        questionPage.value,
        questionQuery.size,
        questionQuery.keyword,
        questionQuery.status,
      ),
      api.documents(id, documentPage.value),
      api.questionSummary(id),
    ])
    if (alive && identity === revision && current === contentRevision) {
      summary.value = counts
      questions.value = q.list
      selectedQuestions.value = selectedQuestions.value.filter((key) =>
        q.list.some(
          (question) =>
            question.id === key &&
            question.status === 'confirmed' &&
            question.version === selectedVersions.get(String(key)),
        ),
      )
      questionTotal.value = q.total
      documents.value = d.list
      documentTotal.value = d.total
    }
  } catch (e) {
    if (alive && identity === revision && current === contentRevision) notifyError(e)
  } finally {
    if (alive && identity === revision && current === contentRevision) contentLoading.value = false
  }
}
function filterQuestions() {
  if (busy.value || contentLoading.value) return
  questionQuery.keyword = filters.keyword.trim()
  questionQuery.status = filters.status
  questionPage.value = 1
  clearSelection()
  void loadContent()
}
function resetQuestionFilters() {
  if (busy.value || contentLoading.value) return
  filters.keyword = ''
  filters.status = ''
  filterQuestions()
}
function questionSizeChange(size: number) {
  if (busy.value) return
  questionQuery.size = size
  questionPage.value = 1
  clearSelection()
  void loadContent()
}
function choosePdf(files: FileItem[]) {
  const selected = files[0]?.file
  if (
    selected &&
    (!selected.name.toLowerCase().endsWith('.pdf') ||
      selected.size > 20 * 1024 * 1024 ||
      selected.size === 0)
  ) {
    notifyError(new Error('invalid_pdf_selection'), '请选择不超过20MiB的非空PDF文件')
    pdfFiles.value = []
    return
  }
  pdfFiles.value = files
}
async function uploadPdf() {
  if (busy.value || !bank.value || !pdfFile.value) return false
  if (pdfFile.value.size > 20 * 1024 * 1024 || !pdfFile.value.name.toLowerCase().endsWith('.pdf')) {
    notifyError(new Error('请选择不超过20MiB的PDF文件'))
    return false
  }
  const current = revision,
    id = bank.value.id
  busy.value = true
  try {
    await api.uploadPdf(id, pdfFile.value)
    if (alive && current === revision) {
      pdfVisible.value = false
      pdfFiles.value = []
      documentPage.value = 1
      await loadContent()
      Message.success('PDF离线解析完成，请核对批次后开始生成草稿')
    }
    return true
  } catch (e) {
    if (alive && current === revision) notifyError(e)
    return false
  } finally {
    if (alive && current === revision) busy.value = false
  }
}
async function startDocument(id: string) {
  if (busy.value) return
  const current = revision
  busy.value = true
  try {
    await api.startDocument(id)
    if (alive && current === revision) {
      await refreshLists()
      Message.success('批次已进入生成队列')
    }
  } catch (e) {
    if (alive && current === revision) notifyError(e)
  } finally {
    if (alive && current === revision) busy.value = false
  }
}
function questionPageChange(value: number) {
  if (busy.value) return
  clearSelection()
  questionPage.value = value
  void loadContent()
}
function documentPageChange(value: number) {
  if (busy.value) return
  documentPage.value = value
  void loadContent()
}
async function submit() {
  if (singlePending.value) {
    if (singlePaused.value) {
      singlePolls = 0
      await checkSingleTask()
    }
    return false
  }
  if (busy.value || !bank.value || !text.value.trim()) return false
  const current = revision,
    id = bank.value.id
  busy.value = true
  singleStatus.value = ''
  try {
    const task = await api.submit(id, text.value.trim())
    if (alive && current === revision) {
      singlePolls = 0
      singlePaused.value = false
      await acceptSingleTask(task)
      scheduleSinglePolling()
    }
  } catch (e) {
    if (alive && current === revision) notifyError(e)
  } finally {
    if (alive && current === revision) busy.value = false
  }
  return false
}
async function changeQuestion(question: PublicQuestionRow, kind: 'withdraw' | 'deleteQuestion') {
  if (busy.value || (kind === 'withdraw') !== (question.status === 'published')) return
  const current = revision
  busy.value = true
  try {
    await api[kind](question.id, question.version)
    if (alive && current === revision) {
      clearSelection()
      await loadContent()
      Message.success(kind === 'withdraw' ? '已下架，请重新确认后发布' : '题目已删除')
    }
  } catch (e) {
    if (alive && current === revision) notifyError(e)
  } finally {
    if (alive && current === revision) busy.value = false
  }
}
watch(
  () => (route.name === 'AdminBankDetailView' ? route.params.id : undefined),
  (id, previousId) => {
    if (id && bank.value?.id !== id) void load()
    else if (id && !previousId) void refreshLists()
    else if (!id) {
      stopPolling()
      stopSinglePolling()
    }
  },
  { immediate: true },
)
</script>
<template>
  <a-card :bordered="false" class="admin-users-card bank-detail-card">
    <template #title>
      <h2 class="bank-name" :title="bank?.name">{{ bank?.name || '题库管理' }}</h2>
    </template>
    <div v-if="bank" data-test="bank-header" class="bank-header">
      <div class="bank-meta">
        <span
          >{{ bank.examType === 'career_a' ? '事业编 A 类' : '上海市考' }} · {{ bankSubject }}</span
        >
        <span>创建时间 {{ bankCreatedAt }}</span>
      </div>
      <div class="bank-counts">
        <span
          >题目总数 <strong>{{ summary?.totalCount ?? '—' }}</strong></span
        >
        <span
          >未确认 <strong>{{ summary?.draftCount ?? '—' }}</strong></span
        >
        <span
          >已确认 <strong>{{ summary?.confirmedCount ?? '—' }}</strong></span
        >
        <span
          >已发布 <strong>{{ summary?.publishedCount ?? '—' }}</strong></span
        >
      </div>
    </div>
    <a-empty v-if="error && !bank" description="题库加载失败，请返回列表后重试" />
    <template v-if="bank">
      <a-form
        data-test="question-filter"
        :model="filters"
        layout="horizontal"
        auto-label-width
        :disabled="busy || loading || contentLoading"
        @submit-success="filterQuestions"
      >
        <div class="user-filter-area">
          <div class="user-filter-form">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12" :xl="8">
                <a-form-item label="题目名称"
                  ><a-input
                    v-model="filters.keyword"
                    :input-attrs="{ id: 'question-keyword' }"
                    placeholder="请输入题目名称"
                    allow-clear
                /></a-form-item>
              </a-col>
              <a-col :xs="24" :md="12" :xl="8">
                <a-form-item label="题目状态"
                  ><a-select
                    v-model="filters.status"
                    input-id="question-status"
                    aria-label="题目状态"
                  >
                    <a-option value="">全部</a-option><a-option value="draft">未确认</a-option>
                    <a-option value="confirmed">已确认</a-option
                    ><a-option value="published">已发布</a-option>
                  </a-select></a-form-item
                >
              </a-col>
            </a-row>
          </div>
          <div class="user-filter-actions">
            <a-button
              html-type="submit"
              type="primary"
              :disabled="busy || loading || contentLoading"
              ><template #icon><IconSearch /></template>查询</a-button
            >
            <a-button
              data-test="reset-question-filters"
              :disabled="busy || loading || contentLoading"
              @click="resetQuestionFilters"
              ><template #icon><IconRefresh /></template>清空</a-button
            >
          </div>
        </div>
      </a-form>
      <a-divider />
      <div data-test="question-toolbar" class="user-toolbar bank-toolbar">
        <div class="bank-toolbar-group">
          <a-dropdown-button
            type="primary"
            :disabled="busy"
            @click="pdfVisible = true"
            @select="openManual"
          >
            从 PDF 导入
            <template #icon><IconDown /></template>
            <template #content
              ><a-doption value="manual" :disabled="busy">单题录入</a-doption></template
            >
          </a-dropdown-button>
          <a-popconfirm
            :content="`确认发布选中的 ${selectedQuestions.length} 道已审核题目？`"
            @ok="publishSelected"
          >
            <a-button
              data-test="batch-publish"
              type="primary"
              :loading="busy"
              :disabled="busy || !selectedQuestions.length"
              >批量发布</a-button
            >
          </a-popconfirm>
          <span class="bank-selection">已选 {{ selectedQuestions.length }} 道</span>
        </div>
        <div class="bank-toolbar-group bank-toolbar-right">
          <a-button data-test="open-documents" @click="documentVisible = true"
            >PDF 导入记录</a-button
          >
        </div>
      </div>
      <a-table
        :data="questionRows"
        :loading="loading || contentLoading"
        :scroll="{ x: '100%' }"
        :pagination="false"
        row-key="id"
        :row-selection="{ type: 'checkbox', showCheckedAll: true, onlyCurrent: true }"
        :selected-keys="selectedQuestions"
        @selection-change="selectQuestions"
        ><template #columns
          ><a-table-column title="题号" :width="100"
            ><template #cell="{ record }">{{ record.questionNo ?? '—' }}</template></a-table-column
          ><a-table-column title="题目"
            ><template #cell="{ record }">{{ record.draft.stem_md }}</template></a-table-column
          ><a-table-column title="状态" :width="120"
            ><template #cell="{ record }">{{
              record.status === 'published'
                ? '已发布'
                : record.status === 'confirmed'
                  ? '已确认'
                  : '未确认'
            }}</template></a-table-column
          ><a-table-column title="操作" :width="180"
            ><template #cell="{ record }"
              ><a-button
                type="text"
                :disabled="busy"
                @click="
                  router.push({ path: `/admin/questions/${record.id}`, query: { bankId: bank.id } })
                "
                >{{ record.status === 'published' ? '查看' : '编辑' }}</a-button
              >
              <a-popconfirm
                v-if="record.status === 'published'"
                content="确认下架当前题目？下架后须重新确认才能发布。"
                :disabled="busy"
                @ok="changeQuestion(record, 'withdraw')"
              >
                <a-button type="text" status="warning" :disabled="busy">下架</a-button>
              </a-popconfirm>
              <a-popconfirm
                v-else
                content="确认删除当前题目？"
                :disabled="busy"
                @ok="changeQuestion(record, 'deleteQuestion')"
              >
                <a-button
                  :data-test="`delete-${record.id}`"
                  type="text"
                  status="danger"
                  :disabled="busy"
                  >删除</a-button
                >
              </a-popconfirm>
            </template></a-table-column
          ></template
        ><template #empty
          ><a-empty description="题库暂无题目，导入材料生成草稿后会显示在这里" /></template
      ></a-table>
      <div class="user-pagination">
        <span>共 {{ questionTotal }} 道题目</span
        ><a-pagination
          :current="questionPage"
          :total="questionTotal"
          :page-size="questionQuery.size"
          :page-size-options="[20, 50, 100]"
          show-page-size
          aria-label="题目列表分页"
          @change="questionPageChange"
          @page-size-change="questionSizeChange"
        />
      </div>
    </template>
  </a-card>
  <a-drawer
    v-model:visible="documentVisible"
    title="PDF导入批次"
    placement="right"
    width="min(1000px, 100vw)"
    :footer="false"
    unmount-on-close
  >
    <a-table
      :data="documents"
      :loading="contentLoading"
      :scroll="{ x: 900 }"
      :pagination="false"
      row-key="id"
      ><template #columns
        ><a-table-column title="文件" data-index="sourceName" /><a-table-column
          title="候选题数"
          data-index="candidateCount"
        /><a-table-column title="状态"
          ><template #cell="{ record }">{{ documentStatus(record) }}</template></a-table-column
        ><a-table-column title="审核进度"
          ><template #cell="{ record }"
            >未确认 {{ record.reviewingCount }} · 已确认 {{ record.confirmedCount ?? 0 }} · 已发布
            {{ record.publishedCount ?? 0 }} · 已删除 {{ record.deletedCount ?? 0 }}</template
          ></a-table-column
        >
        <a-table-column title="警告"
          ><template #cell="{ record }">{{
            record.report.warnings?.length ? '材料需复核，请检查原文与题目边界' : '—'
          }}</template></a-table-column
        ><a-table-column title="操作"
          ><template #cell="{ record }"
            ><a-popconfirm
              content="将逐题调用模型，缺答案时联网检索，可能产生费用。确认材料范围并开始？"
              @ok="startDocument(record.id)"
              ><a-button type="text" :disabled="record.status !== 'staged' || busy"
                >开始生成草稿</a-button
              ></a-popconfirm
            ></template
          ></a-table-column
        ></template
      ></a-table
    >
    <div class="user-pagination">
      <span>共 {{ documentTotal }} 个批次</span
      ><a-pagination
        :current="documentPage"
        :total="documentTotal"
        :page-size="20"
        aria-label="PDF导入批次分页"
        @change="documentPageChange"
      />
    </div>
  </a-drawer>
  <a-modal
    :visible="pdfVisible"
    title="批量导入 PDF"
    title-align="start"
    ok-text="上传并离线解析"
    cancel-text="取消"
    :on-before-ok="uploadPdf"
    :ok-loading="busy"
    :ok-button-props="{ disabled: !pdfFile || busy }"
    :cancel-button-props="{ disabled: busy }"
    :mask-closable="false"
    :closable="!busy"
    :esc-to-close="!busy"
    unmount-on-close
    @cancel="pdfVisible = false"
    ><p>
      支持不超过20MiB的文本层PDF，每批最多100题。上传只做离线切题，核对批次后再开始模型生成；扫描件、缺图或边界不清的题目需额外处理。
    </p>
    <a-upload
      :file-list="pdfFiles"
      accept="application/pdf,.pdf"
      :auto-upload="false"
      :limit="1"
      draggable
      :show-upload-button="{ showOnExceedLimit: false }"
      :show-preview-button="false"
      :show-link="false"
      :show-retry-button="false"
      :disabled="busy"
      tip="拖拽或点击选择 PDF，最大20MiB；选择后点击底部按钮提交"
      @update:file-list="choosePdf"
    />
  </a-modal>
  <a-modal
    :visible="importVisible"
    title="单题录入"
    title-align="start"
    :ok-text="singlePending && singlePaused ? '查询解析进度' : '解析并录入'"
    cancel-text="取消"
    :on-before-ok="submit"
    :ok-loading="importBusy"
    :ok-button-props="{ disabled: importBusy || !text.trim() }"
    :cancel-button-props="{ disabled: importBusy }"
    :mask-closable="false"
    :esc-to-close="!importBusy"
    :closable="!importBusy"
    @cancel="importVisible = false"
  >
    <p>
      粘贴一道完整题目和选项，可包含答案及解析。点击“解析并录入”将调用模型，可能产生费用；结果仍须原导入人确认。
    </p>
    <a-textarea
      v-model="text"
      data-test="single-text"
      :disabled="importBusy || singlePending"
      :max-length="12000"
      :auto-size="{ minRows: 10, maxRows: 18 }"
    />
    <p v-if="singleStatus" role="status">
      {{ singleStatus }}<template v-if="singleTask"> · 任务 {{ singleTask.id }}</template>
    </p>
  </a-modal>
</template>
<style scoped>
.bank-detail-card :deep(.arco-card-header-title) {
  min-width: 0;
}
.bank-header {
  display: grid;
  gap: 10px;
  padding: 0 0 20px;
}
.bank-name {
  margin: 0;
  color: var(--color-text-1);
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bank-meta,
.bank-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  color: var(--color-text-3);
  font-size: 13px;
  font-weight: 400;
  line-height: 1.6;
}
.bank-counts {
  color: var(--color-text-2);
}
.bank-counts strong {
  margin-left: 4px;
  color: var(--color-text-1);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.bank-toolbar,
.bank-toolbar-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.bank-toolbar-right {
  margin-left: auto;
}
.bank-selection {
  color: var(--color-text-3);
  font-size: 13px;
}
</style>
