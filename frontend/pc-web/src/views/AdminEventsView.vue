<script setup lang="ts">
import { IconRefresh, IconSearch } from '@arco-design/web-vue/es/icon'
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { createAdminControlApi, useAuthStore, notifyError, type AccountEvent } from '@quest/shared'
const api = createAdminControlApi(useAuthStore())
const filters = reactive({ userId: '', kind: '', page: 1, size: 20 })
const data = ref<{ list: AccountEvent[]; total: number } | null>(null)
const loadFailed = ref(false)
const busy = ref(false)
let alive = true,
  version = 0
onBeforeUnmount(() => {
  alive = false
  version++
})
async function load(page = filters.page, size = filters.size) {
  filters.page = page
  filters.size = size
  const current = ++version
  loadFailed.value = false
  busy.value = true
  data.value = null
  try {
    const result = await api.events({ ...filters })
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
function time(value: string) {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
function resetFilters() {
  filters.userId = ''
  filters.kind = ''
  void load(1)
}
onMounted(() => load())
</script>
<template>
  <a-card class="admin-users-card" title="登录与操作日志" :bordered="false"
    ><a-form
      :model="filters"
      layout="horizontal"
      auto-label-width
      :disabled="busy"
      @submit-success="load(1)"
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
              ><a-form-item label="日志类型"
                ><a-select v-model="filters.kind"
                  ><a-option value="">全部类型</a-option><a-option value="login">登录</a-option
                  ><a-option value="operation">操作</a-option></a-select
                ></a-form-item
              ></a-col
            ></a-row
          >
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
    <div class="user-toolbar">
      <a-button :loading="busy" @click="load()"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <a-empty v-if="loadFailed" description="日志加载失败，请稍后重试" /><a-table
      v-else
      row-key="id"
      :data="data?.list ?? []"
      :loading="busy"
      :pagination="false"
      :scroll="{ x: 1320 }"
      ><template #columns
        ><a-table-column title="时间" :width="180"
          ><template #cell="{ record }">{{ time(record.created_at) }}</template></a-table-column
        ><a-table-column
          title="用户"
          :width="120"
          data-index="username"
          ellipsis
          tooltip
        /><a-table-column title="用户 ID" :width="90"
          ><template #cell="{ record }">{{ record.actor ?? '—' }}</template></a-table-column
        ><a-table-column
          title="动作"
          :width="220"
          data-index="action"
          ellipsis
          tooltip
        /><a-table-column title="结果" :width="90"
          ><template #cell="{ record }"
            ><a-tag :color="record.outcome === 'ok' ? 'green' : 'red'">{{
              record.outcome === 'ok' ? '成功' : '失败'
            }}</a-tag></template
          ></a-table-column
        ><a-table-column title="目标" :width="140" ellipsis tooltip
          ><template #cell="{ record }">{{ record.target_id || '—' }}</template></a-table-column
        ><a-table-column title="来源" :width="140" ellipsis tooltip
          ><template #cell="{ record }">{{ record.source || '—' }}</template></a-table-column
        ><a-table-column title="说明" :width="180" ellipsis tooltip
          ><template #cell="{ record }">{{ record.detail || '—' }}</template></a-table-column
        ><a-table-column title="Trace ID" :width="160" ellipsis tooltip
          ><template #cell="{ record }">{{ record.trace_id || '—' }}</template></a-table-column
        ></template
      ><template #empty
        ><a-empty :description="busy ? '正在加载日志' : '没有符合筛选条件的日志'" /></template
    ></a-table>
    <div v-if="data" class="user-pagination">
      <span>共 {{ data.total }} 条日志</span
      ><a-pagination
        :total="data.total"
        :current="filters.page"
        :page-size="filters.size"
        :page-size-options="[20, 50, 100]"
        show-page-size
        :disabled="busy"
        aria-label="日志分页"
        @change="(page: number) => load(page)"
        @page-size-change="(size: number) => load(1, size)"
      /></div
  ></a-card>
</template>
