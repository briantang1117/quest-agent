<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { IconRefresh, IconSearch } from '@arco-design/web-vue/es/icon'
import {
  actionLabel,
  AI_ACTIONS,
  aiAuditParams,
  createAiAuditApi,
  notifyError,
  prettyJson,
  useAuthStore,
  type AiCallDetail,
  type AiCallPage,
  type AiCallRow,
} from '@quest/shared'
const api = createAiAuditApi(useAuthStore())
const data = ref<AiCallPage | null>(null)
const loading = ref(false),
  loadFailed = ref(false),
  detail = ref<AiCallDetail | null>(null),
  detailLoading = ref(false)
const filters = reactive({ action: '', outcome: '' })
let revision = 0
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})
function formatDate(value: string) {
  const time = Date.parse(value)
  return Number.isFinite(time) ? dateFormat.format(time) : '时间不可用'
}
// 耗时用秒展示更直观，毫秒级保留整数。
function formatElapsed(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}
async function load(page = data.value?.page ?? 1, size = data.value?.size ?? 20) {
  const current = ++revision
  loading.value = true
  loadFailed.value = false
  try {
    const value = await api.list(aiAuditParams(filters, page, size))
    if (current === revision) data.value = value
  } catch (error) {
    if (current === revision) {
      loadFailed.value = true
      notifyError(error)
    }
  } finally {
    if (current === revision) loading.value = false
  }
}
function resetFilters() {
  filters.action = ''
  filters.outcome = ''
  void load(1)
}
async function open(record: AiCallRow) {
  detailLoading.value = true
  detail.value = { row: record, requestJson: '', responseText: null }
  try {
    detail.value = await api.detail(record.id)
  } catch (error) {
    detail.value = null
    notifyError(error)
  } finally {
    detailLoading.value = false
  }
}
onMounted(() => void load())
</script>
<template>
  <a-card class="admin-users-card" title="AI 调用记录" :bordered="false">
    <!-- 与用户管理保持一致：a-form + 栅格筛选 + 分隔线 + 工具栏。 -->
    <a-form
      data-test="ai-log-filter"
      :model="filters"
      layout="horizontal"
      auto-label-width
      :disabled="loading"
      @submit-success="load(1)"
    >
      <div class="user-filter-area">
        <div class="user-filter-form">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12" :xl="8">
              <a-form-item label="调用类型"
                ><a-select
                  v-model="filters.action"
                  input-id="ai-log-action"
                  aria-label="调用类型"
                  placeholder="全部类型"
                  allow-clear
                  ><a-option v-for="item in AI_ACTIONS" :key="item.value" :value="item.value">{{
                    item.label
                  }}</a-option></a-select
                ></a-form-item
              >
            </a-col>
            <a-col :xs="24" :md="12" :xl="8">
              <a-form-item label="调用结果"
                ><a-select
                  v-model="filters.outcome"
                  input-id="ai-log-outcome"
                  aria-label="调用结果"
                  placeholder="全部结果"
                  allow-clear
                  ><a-option value="ok">成功</a-option
                  ><a-option value="failed">失败</a-option></a-select
                ></a-form-item
              >
            </a-col>
          </a-row>
        </div>
        <div class="user-filter-actions">
          <a-button data-test="search-logs" html-type="submit" :disabled="loading" type="primary"
            ><template #icon><IconSearch /></template>查询</a-button
          >
          <a-button data-test="reset-logs" :disabled="loading" @click="resetFilters"
            ><template #icon><IconRefresh /></template>清空</a-button
          >
        </div>
      </div>
    </a-form>
    <a-divider />
    <div class="user-toolbar">
      <a-button data-test="refresh-logs" :loading="loading" @click="load()"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <a-empty v-if="loadFailed" description="日志加载失败，请稍后重试" />
    <a-table
      v-else
      row-key="id"
      :data="data?.list ?? []"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: '100%' }"
    >
      <template #columns>
        <a-table-column title="时间" :width="170"
          ><template #cell="{ record }">{{
            formatDate(record.createdAt)
          }}</template></a-table-column
        >
        <a-table-column title="类型" :width="130"
          ><template #cell="{ record }">{{ actionLabel(record.action) }}</template></a-table-column
        >
        <a-table-column title="结果" :width="90"
          ><template #cell="{ record }"
            ><a-tag :color="record.outcome === 'ok' ? 'green' : 'red'">{{
              record.outcome === 'ok' ? '成功' : '失败'
            }}</a-tag></template
          ></a-table-column
        >
        <a-table-column title="调用人" :width="150"
          ><template #cell="{ record }">{{ record.actor || '系统' }}</template></a-table-column
        >
        <a-table-column title="耗时" :width="110"
          ><template #cell="{ record }">{{
            formatElapsed(record.elapsedMs)
          }}</template></a-table-column
        >
        <a-table-column title="重试" :width="80"
          ><template #cell="{ record }">{{ record.attempts }} 次</template></a-table-column
        >
        <a-table-column title="失败原因" ellipsis tooltip
          ><template #cell="{ record }">{{ record.error || '—' }}</template></a-table-column
        >
        <a-table-column title="操作" :width="90"
          ><template #cell="{ record }"
            ><a-button data-test="open-detail" type="text" size="small" @click="open(record)"
              >查看</a-button
            ></template
          ></a-table-column
        >
      </template>
      <template #empty
        ><a-empty :description="loading ? '正在加载真实调用记录' : '没有符合筛选条件的调用记录'"
      /></template>
    </a-table>
    <div v-if="data" class="user-pagination">
      <span>共 {{ data.total }} 条调用记录</span
      ><a-pagination
        :total="data.total"
        :current="data.page"
        :page-size="data.size"
        :page-size-options="[20, 50, 100]"
        show-page-size
        aria-label="调用记录分页"
        @change="(page: number) => load(page)"
        @page-size-change="(size: number) => load(1, size)"
      />
    </div>
  </a-card>

  <a-drawer
    :visible="detail !== null"
    title="调用详情"
    :width="640"
    :footer="false"
    unmount-on-close
    @cancel="detail = null"
  >
    <a-spin :loading="detailLoading" style="width: 100%">
      <template v-if="detail">
        <a-descriptions :column="1" size="small" bordered>
          <a-descriptions-item label="时间">{{
            formatDate(detail.row.createdAt)
          }}</a-descriptions-item>
          <a-descriptions-item label="类型">{{
            actionLabel(detail.row.action)
          }}</a-descriptions-item>
          <a-descriptions-item label="结果">
            <a-tag :color="detail.row.outcome === 'ok' ? 'green' : 'red'">{{
              detail.row.outcome === 'ok' ? '成功' : '失败'
            }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="调用人">{{ detail.row.actor || '系统' }}</a-descriptions-item>
          <a-descriptions-item label="耗时">{{
            formatElapsed(detail.row.elapsedMs)
          }}</a-descriptions-item>
          <a-descriptions-item label="重试次数">{{ detail.row.attempts }} 次</a-descriptions-item>
          <a-descriptions-item v-if="detail.row.model" label="模型">{{
            detail.row.model
          }}</a-descriptions-item>
          <a-descriptions-item v-if="detail.row.error" label="失败原因">{{
            detail.row.error
          }}</a-descriptions-item>
          <a-descriptions-item label="Trace">{{ detail.row.traceId || '—' }}</a-descriptions-item>
        </a-descriptions>
        <h3 class="detail-heading">请求内容</h3>
        <pre class="detail-pre">{{ prettyJson(detail.requestJson) }}</pre>
        <h3 class="detail-heading">模型原始输出</h3>
        <pre v-if="detail.responseText" class="detail-pre">{{ detail.responseText }}</pre>
        <p v-else class="detail-empty">本次调用没有返回内容（失败时这里会保留被拒绝的原文）。</p>
      </template>
    </a-spin>
  </a-drawer>
</template>
<style scoped>
/* 卡片、筛选区、工具栏、分页复用 admin-layout.css 的用户管理样式，保证两个页面完全对齐。 */
.detail-heading {
  margin: 20px 0 8px;
  font-size: 14px;
}
.detail-pre {
  max-height: 320px;
  overflow: auto;
  padding: 12px;
  background: var(--color-fill-1);
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.detail-empty {
  color: var(--color-text-3);
  font-size: 13px;
}
</style>
