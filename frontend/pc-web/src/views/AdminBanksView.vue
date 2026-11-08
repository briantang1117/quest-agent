<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message, type FormInstance } from '@arco-design/web-vue'
import { IconSearch, IconRefresh, IconPlus } from '@arco-design/web-vue/es/icon'
import { createPublicBankApi, notifyError, useAuthStore, type PublicBank } from '@quest/shared'
const api = createPublicBankApi(useAuthStore()),
  router = useRouter()
const banks = ref<PublicBank[]>([])
const loading = ref(false),
  busy = ref(false),
  createVisible = ref(false),
  error = ref('')
const form = reactive({ name: '', examType: 'career_a' })
const bankForm = ref<FormInstance>()
const filters = reactive({ keyword: '', examType: '' })
const query = reactive({ keyword: '', examType: '' })
const bankPage = ref(1),
  bankSize = ref(20),
  bankTotal = ref(0)
function filter() {
  query.keyword = filters.keyword.trim()
  query.examType = filters.examType
  bankPage.value = 1
  void load()
}
function resetFilters() {
  filters.keyword = ''
  filters.examType = ''
  filter()
}
function bankSizeChange(size: number) {
  bankSize.value = size
  bankPage.value = 1
  void load()
}
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
function formatDate(value: string) {
  const time = Date.parse(value)
  return Number.isFinite(time) ? dateFormat.format(time) : '时间不可用'
}
let listRevision = 0
function bankPageChange(page: number) {
  bankPage.value = page
  void load()
}
let alive = true
onBeforeUnmount(() => {
  alive = false
  listRevision++
})
async function load() {
  const current = ++listRevision
  loading.value = true
  error.value = ''
  banks.value = []
  try {
    const result = await api.list(bankPage.value, bankSize.value, query.keyword, query.examType)
    if (alive && current === listRevision) {
      banks.value = result.list
      bankTotal.value = result.total
    }
  } catch (e) {
    if (alive && current === listRevision) {
      error.value = '加载失败'
      notifyError(e)
    }
  } finally {
    if (alive && current === listRevision) loading.value = false
  }
}
async function create() {
  if (busy.value || !bankForm.value) return false
  form.name = form.name.trim()
  if (await bankForm.value.validate()) return false
  busy.value = true
  try {
    await api.create({
      name: form.name.trim(),
      examType: form.examType,
      subject: form.examType === 'career_a' ? 'vocational_aptitude' : 'administrative_aptitude',
    })
    createVisible.value = false
    form.name = ''
    await load()
    Message.success('题库已创建')
    return true
  } catch (e) {
    notifyError(e)
    return false
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <a-card title="公共题库" :bordered="false" class="admin-users-card">
    <a-form
      data-test="bank-filter"
      :model="filters"
      layout="horizontal"
      auto-label-width
      :disabled="loading || busy"
      @submit-success="filter"
    >
      <div class="user-filter-area">
        <div class="user-filter-form">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="题库名称"
                ><a-input
                  v-model="filters.keyword"
                  :input-attrs="{ id: 'bank-keyword' }"
                  placeholder="请输入题库名称"
                  :max-length="128"
                  allow-clear /></a-form-item
            ></a-col>
            <a-col :xs="24" :md="12" :xl="8"
              ><a-form-item label="考试科目"
                ><a-select
                  v-model="filters.examType"
                  input-id="bank-exam-type"
                  aria-label="考试科目"
                  ><a-option value="">全部</a-option
                  ><a-option value="career_a">事业编 A 类 · 职测</a-option
                  ><a-option value="shanghai_civil">上海市考 · 行测</a-option></a-select
                ></a-form-item
              ></a-col
            >
          </a-row>
        </div>
        <div class="user-filter-actions">
          <a-button html-type="submit" type="primary" :disabled="loading || busy"
            ><template #icon><IconSearch /></template>查询</a-button
          ><a-button
            data-test="reset-bank-filters"
            :disabled="loading || busy"
            @click="resetFilters"
            ><template #icon><IconRefresh /></template>清空</a-button
          >
        </div>
      </div>
    </a-form>
    <a-divider />
    <div class="user-toolbar">
      <a-button type="primary" :disabled="busy" @click="createVisible = true"
        ><template #icon><IconPlus /></template>新建题库</a-button
      ><a-button :loading="loading" :disabled="busy" @click="load"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <a-empty v-if="error" description="题库列表加载失败，请刷新重试" />
    <a-table
      v-if="!error"
      :data="banks"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: '100%' }"
      row-key="id"
    >
      <template #columns
        ><a-table-column title="题库名称" data-index="name" /><a-table-column title="考试科目"
          ><template #cell="{ record }">{{
            record.examType === 'career_a' ? '事业编 A 类 · 职测' : '上海市考 · 行测'
          }}</template></a-table-column
        ><a-table-column title="创建时间" :width="180"
          ><template #cell="{ record }">{{
            formatDate(record.createdAt)
          }}</template></a-table-column
        >
        <a-table-column title="操作" :width="140"
          ><template #cell="{ record }"
            ><a-button type="text" @click="router.push(`/admin/banks/${record.id}`)"
              >管理题库</a-button
            ></template
          ></a-table-column
        ></template
      >
    </a-table>
    <div v-if="!error" class="user-pagination" data-test="bank-pagination">
      <span>共 {{ bankTotal }} 个匹配题库</span
      ><a-pagination
        :current="bankPage"
        :page-size="bankSize"
        :total="bankTotal"
        :page-size-options="[20, 50, 100]"
        show-page-size
        aria-label="题库列表分页"
        @change="bankPageChange"
        @page-size-change="bankSizeChange"
      />
    </div>
  </a-card>
  <a-modal
    :visible="createVisible"
    title="新建公共题库"
    title-align="start"
    ok-text="创建"
    cancel-text="取消"
    :on-before-ok="create"
    :ok-loading="busy"
    :cancel-button-props="{ disabled: busy }"
    :mask-closable="false"
    :esc-to-close="!busy"
    :closable="!busy"
    unmount-on-close
    @cancel="createVisible = false"
    ><a-form
      ref="bankForm"
      :model="form"
      layout="vertical"
      :disabled="busy"
      @submit-success="create"
      ><a-form-item
        field="name"
        label="题库名称"
        :rules="[{ required: true, message: '请输入题库名称' }]"
        ><a-input v-model="form.name" :max-length="128" /></a-form-item
      ><a-form-item field="examType" label="考试科目"
        ><a-select v-model="form.examType"
          ><a-option value="career_a">事业编 A 类 · 职测</a-option
          ><a-option value="shanghai_civil">上海市考 · 行测</a-option></a-select
        ></a-form-item
      ></a-form
    ></a-modal
  >
</template>
