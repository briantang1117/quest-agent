<script setup lang="ts">
import { IconRefresh, IconSearch } from '@arco-design/web-vue/es/icon'
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { createAdminControlApi, useAuthStore, notifyError, type Statistics } from '@quest/shared'
const route = useRoute()
const api = createAdminControlApi(useAuthStore())
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const filters = reactive({
  userId: String(route.query.userId || ''),
  from: today.slice(0, 8) + '01',
  to: today,
})
const data = ref<Statistics | null>(null)
const loadFailed = ref(false)
const busy = ref(false)
let version = 0,
  alive = true
onBeforeUnmount(() => {
  alive = false
  version++
})
async function load() {
  const current = ++version
  loadFailed.value = false
  busy.value = true
  data.value = null
  try {
    const result = await api.statistics({ ...filters })
    if (alive && current === version) data.value = result
  } catch (e) {
    if (alive && current === version) {
      loadFailed.value = true
      notifyError(e)
    }
  } finally {
    if (alive && current === version) busy.value = false
  }
}
function resetFilters() {
  filters.userId = ''
  filters.from = today.slice(0, 8) + '01'
  filters.to = today
  void load()
}
onMounted(load)
</script>
<template>
  <a-card class="admin-users-card" title="接口与 AI 使用统计" :bordered="false"
    ><a-form
      :model="filters"
      layout="horizontal"
      auto-label-width
      :disabled="busy"
      @submit-success="load"
      ><div class="user-filter-area">
        <div class="user-filter-form">
          <a-row :gutter="16"
            ><a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="用户 ID"
                ><a-input
                  v-model="filters.userId"
                  placeholder="全部用户"
                  allow-clear /></a-form-item></a-col
            ><a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="开始日期"
                ><a-date-picker
                  v-model="filters.from"
                  value-format="YYYY-MM-DD"
                  :allow-clear="false" /></a-form-item></a-col
            ><a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="结束日期"
                ><a-date-picker
                  v-model="filters.to"
                  value-format="YYYY-MM-DD"
                  :allow-clear="false" /></a-form-item></a-col
          ></a-row>
        </div>
        <div class="user-filter-actions">
          <a-button html-type="submit" :disabled="busy" type="primary"
            ><template #icon><IconSearch /></template>查询</a-button
          ><a-button :disabled="busy" @click="resetFilters"
            ><template #icon><IconRefresh /></template>清空</a-button
          >
        </div>
      </div></a-form
    ><a-divider />
    <p class="statistics-hint">按上海时间统计，包含结束日，最多查询 366 天。</p>
    <a-empty v-if="loadFailed" description="统计加载失败，请稍后重试" /><template v-else
      ><section class="statistics-section" aria-labelledby="ai-statistics-title">
        <h2 id="ai-statistics-title" class="user-toolbar-title">AI 能力与模型</h2>
        <p class="statistics-hint">按实际模型请求计数，同一轮对话可能调用多次。</p>
        <a-table :data="data?.ai ?? []" :loading="busy" :pagination="false" :scroll="{ x: 960 }"
          ><template #columns
            ><a-table-column
              title="模型"
              :width="220"
              data-index="model"
              ellipsis
              tooltip /><a-table-column
              title="能力"
              :width="140"
              data-index="capability"
              ellipsis
              tooltip /><a-table-column
              title="调用数"
              :width="100"
              align="right"
              data-index="calls"
              ellipsis
              tooltip /><a-table-column
              title="失败数"
              :width="100"
              align="right"
              data-index="failed"
              ellipsis
              tooltip /><a-table-column
              title="输入 Token"
              :width="130"
              align="right"
              data-index="input_tokens"
              ellipsis
              tooltip /><a-table-column
              title="输出 Token"
              :width="130"
              align="right"
              data-index="output_tokens"
              ellipsis
              tooltip /><a-table-column
              title="未结算预占"
              :width="140"
              align="right"
              data-index="pending_tokens"
              ellipsis
              tooltip /></template
          ><template #empty
            ><a-empty :description="busy ? '正在加载统计' : '所选范围内暂无调用记录'" /></template
        ></a-table>
      </section>
      <a-divider />
      <section class="statistics-section" aria-labelledby="api-statistics-title">
        <h2 id="api-statistics-title" class="user-toolbar-title">接口调用</h2>
        <p class="statistics-hint">按接口请求计数，与模型调用次数分别统计。</p>
        <a-table
          :data="data?.api ?? []"
          :loading="busy"
          :pagination="{ pageSize: 20, showTotal: true }"
          :scroll="{ x: 720 }"
          ><template #columns
            ><a-table-column title="方法" :width="110"
              ><template #cell="{ record }"
                ><a-tag>{{ record.method }}</a-tag></template
              ></a-table-column
            ><a-table-column title="接口" data-index="route" ellipsis tooltip /><a-table-column
              title="调用数"
              :width="120"
              align="right"
              data-index="calls"
              ellipsis
              tooltip /><a-table-column
              title="失败数"
              :width="120"
              align="right"
              data-index="failed"
              ellipsis
              tooltip /></template
          ><template #empty
            ><a-empty :description="busy ? '正在加载统计' : '所选范围内暂无调用记录'" /></template
        ></a-table></section></template
  ></a-card>
</template>
<style scoped>
.statistics-section h2 {
  margin: 0 0 8px;
}
.statistics-hint {
  margin: 0 0 16px;
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
