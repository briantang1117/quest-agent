<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from '@arco-design/web-vue'
import { IconSearch, IconRefresh, IconPlus } from '@arco-design/web-vue/es/icon'
const userForm = ref<FormInstance>()
import {
  createUserApi,
  notifyError,
  PASSWORD_HINT,
  useAuthStore,
  type UserPage,
  type UserProfile,
  type UserStatus,
} from '@quest/shared'
import { displayNameRule, passwordRule, usernameRule } from '../form-rules'
const auth = useAuthStore()
const api = createUserApi(auth)
const filters = reactive({ keyword: '', status: '' as UserStatus | '' })
const query = reactive({ page: 1, size: 20, keyword: '', status: '' as UserStatus | '' })
const data = ref<UserPage | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const success = ref('')
const saving = ref(false)
const mode = ref<'create' | 'status' | 'reset' | null>(null)
const selected = ref<UserProfile | null>(null)
const form = reactive({ username: '', displayName: '', initialPassword: '', newPassword: '' })
const title = computed(() =>
  mode.value === 'create'
    ? '创建普通用户'
    : mode.value === 'reset'
      ? '重置密码'
      : selected.value?.status === 'active'
        ? '禁用账号'
        : '启用账号',
)
let listRevision = 0
let alive = true
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
function formatDate(value: string | null) {
  if (value === null) return '尚未登录'
  const time = Date.parse(value)
  return Number.isFinite(time) ? dateFormat.format(time) : '时间不可用'
}
async function load() {
  const version = ++listRevision
  loading.value = true
  loadFailed.value = false
  data.value = null
  try {
    const result = await api.list({ ...query })
    if (alive && version === listRevision) data.value = result
  } catch (cause) {
    if (alive && version === listRevision) {
      loadFailed.value = true
      notifyError(cause)
    }
  } finally {
    if (alive && version === listRevision) loading.value = false
  }
}
function filter() {
  query.keyword = filters.keyword.trim()
  query.status = filters.status
  query.page = 1
  void load()
}
function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filter()
}
async function submitDialog() {
  if (saving.value) return
  if (mode.value !== 'status') {
    if (!userForm.value || (await userForm.value.validate())) return
  }
  await save()
}
function pageChange(page: number) {
  query.page = page
  void load()
}
function sizeChange(size: number) {
  query.size = size
  query.page = 1
  void load()
}
function open(next: 'create' | 'status' | 'reset', user?: UserProfile) {
  if (saving.value) return
  selected.value = user ?? null
  mode.value = next
  success.value = ''
  form.username = ''
  form.displayName = ''
  form.initialPassword = ''
  form.newPassword = ''
}
function close() {
  if (saving.value) return
  mode.value = null
  selected.value = null
  form.username = ''
  form.displayName = ''
  form.initialPassword = ''
  form.newPassword = ''
}
async function save() {
  if (saving.value || !mode.value) return
  saving.value = true
  try {
    if (mode.value === 'create') {
      await api.create({
        username: form.username,
        displayName: form.displayName.trim(),
        initialPassword: form.initialPassword,
      })
      success.value = '账号已创建。请通过安全渠道向用户交付初始密码，首次登录必须改密。'
    } else if (selected.value) {
      if (mode.value === 'status') {
        const status = selected.value.status === 'active' ? 'disabled' : 'active'
        await api.setStatus(selected.value.id, status)
        success.value =
          status === 'disabled' ? '账号已禁用，已有会话已失效。' : '账号已启用，用户需重新登录。'
      } else {
        await api.resetPassword(selected.value.id, form.newPassword)
        success.value = '密码已重置，已有会话已撤销。请安全交付新密码，用户下次登录必须改密。'
      }
    }
    if (!alive) return
    saving.value = false
    close()
    // 写入成功与列表刷新结果分开提示，避免刷新失败诱导重复写入。
    await load()
  } catch (cause) {
    if (alive) notifyError(cause)
  } finally {
    saving.value = false
  }
}
onMounted(() => void load())
onBeforeUnmount(() => {
  alive = false
  listRevision++
  form.initialPassword = ''
  form.newPassword = ''
})
</script>
<template>
  <a-card class="admin-users-card" title="用户管理" :bordered="false">
    <a-alert v-if="success" type="success" role="status">{{ success }}</a-alert>
    <a-form
      data-test="filter"
      :model="filters"
      layout="horizontal"
      auto-label-width
      :disabled="loading || saving"
      @submit-success="filter"
    >
      <div class="user-filter-area">
        <div class="user-filter-form">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12" :xl="8">
              <a-form-item label="用户名称"
                ><a-input
                  v-model="filters.keyword"
                  :input-attrs="{ id: 'user-keyword' }"
                  placeholder="请输入用户名或展示名"
                  allow-clear
              /></a-form-item>
            </a-col>
            <a-col :xs="24" :md="12" :xl="8">
              <a-form-item label="账号状态"
                ><a-select v-model="filters.status" input-id="user-status" aria-label="账号状态"
                  ><a-option value="">全部</a-option><a-option value="active">启用</a-option
                  ><a-option value="disabled">禁用</a-option></a-select
                ></a-form-item
              >
            </a-col>
          </a-row>
        </div>
        <div class="user-filter-actions">
          <a-button html-type="submit" :disabled="loading || saving" type="primary"
            ><template #icon><IconSearch /></template>查询</a-button
          >
          <a-button data-test="reset-filters" :disabled="loading || saving" @click="resetFilters"
            ><template #icon><IconRefresh /></template>清空</a-button
          >
        </div>
      </div>
    </a-form>
    <a-divider />
    <div class="user-toolbar">
      <a-button data-test="create-user" type="primary" :disabled="saving" @click="open('create')"
        ><template #icon><IconPlus /></template>新增用户</a-button
      >
      <a-button data-test="refresh-users" :loading="loading" :disabled="saving" @click="load"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <a-table
      v-if="!loadFailed"
      row-key="id"
      :data="data?.list ?? []"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: '100%' }"
    >
      <template #columns>
        <a-table-column title="账号" :width="230"
          ><template #cell="{ record }"
            ><strong>{{ record.displayName }}</strong>
            <div class="account-username">{{ record.username }}</div>
            <div class="account-id">ID {{ record.id }}</div></template
          ></a-table-column
        >
        <a-table-column title="状态" :width="100"
          ><template #cell="{ record }"
            ><a-tag :color="record.status === 'active' ? 'green' : 'gray'">{{
              record.status === 'active' ? '启用' : '禁用'
            }}</a-tag></template
          ></a-table-column
        >
        <a-table-column title="密码状态" :width="120"
          ><template #cell="{ record }">{{
            record.mustChangePassword ? '下次登录需改密' : '已设置'
          }}</template></a-table-column
        >
        <a-table-column title="创建时间" :width="160"
          ><template #cell="{ record }">{{
            formatDate(record.createdAt)
          }}</template></a-table-column
        >
        <a-table-column title="最近登录" :width="160"
          ><template #cell="{ record }">{{
            formatDate(record.lastLoginAt)
          }}</template></a-table-column
        >
        <a-table-column title="操作" :width="190"
          ><template #cell="{ record }"
            ><a-space
              ><a-button
                data-test="toggle-status"
                type="text"
                size="small"
                :status="record.status === 'active' ? 'danger' : 'normal'"
                :disabled="saving"
                @click="open('status', record)"
                >{{ record.status === 'active' ? '禁用' : '启用' }}</a-button
              ><a-button
                data-test="reset-password"
                type="text"
                size="small"
                :disabled="saving"
                @click="open('reset', record)"
                >重置密码</a-button
              ></a-space
            ></template
          ></a-table-column
        >
      </template>
      <template #empty
        ><a-empty :description="loading ? '正在加载真实账号' : '没有符合筛选条件的普通用户'"
      /></template>
    </a-table>
    <div v-if="data" class="user-pagination">
      <span>共 {{ data.total }} 个匹配账号</span
      ><a-pagination
        :total="data.total"
        :current="data.page"
        :page-size="data.size"
        :page-size-options="[20, 50, 100]"
        show-page-size
        aria-label="用户列表分页"
        @change="pageChange"
        @page-size-change="sizeChange"
      />
    </div>
  </a-card>
  <a-modal
    :visible="mode !== null"
    :title="title"
    title-align="start"
    modal-class="user-dialog"
    :width="520"
    :mask-closable="false"
    :closable="!saving"
    :esc-to-close="!saving"
    unmount-on-close
    @cancel="close"
  >
    <p v-if="selected" class="modal-account">
      {{ selected.displayName }}（{{ selected.username }}）
    </p>
    <template v-if="mode === 'status'">
      <p>
        {{
          selected?.status === 'active'
            ? '禁用后将立即撤销已有会话，用户无法继续登录。账号及其数据不会被删除。'
            : '启用后用户可使用现有密码重新登录，不会恢复已撤销的会话。'
        }}
      </p>
    </template>
    <a-form
      v-else
      :key="mode ?? 'closed'"
      ref="userForm"
      data-test="user-form"
      :model="form"
      layout="horizontal"
      :label-col-props="{ span: 5 }"
      :wrapper-col-props="{ span: 19 }"
      :disabled="saving"
      @submit-success="save"
    >
      <template v-if="mode === 'create'">
        <a-form-item
          field="username"
          label="用户名"
          :rules="usernameRule"
          extra="小写字母开头，3–32 位，仅含小写字母、数字、下划线"
          ><a-input
            v-model="form.username"
            :input-attrs="{ id: 'create-username', autocomplete: 'off', autocapitalize: 'none' }"
        /></a-form-item>
        <a-form-item field="displayName" label="展示名" :rules="displayNameRule"
          ><a-input
            v-model="form.displayName"
            :input-attrs="{ id: 'create-display-name', autocomplete: 'off' }"
        /></a-form-item>
        <a-form-item
          field="initialPassword"
          label="初始密码"
          :rules="passwordRule"
          :extra="PASSWORD_HINT"
          ><a-input-password
            v-model="form.initialPassword"
            :input-attrs="{ id: 'initial-password', autocomplete: 'new-password' }"
        /></a-form-item>
      </template>
      <template v-else
        ><p>重置将撤销此用户的全部会话，下次登录必须改密。新密码不会再次显示，请安全交付。</p>
        <a-form-item
          field="newPassword"
          label="重置后的密码"
          :rules="passwordRule"
          :extra="PASSWORD_HINT"
          ><a-input-password
            v-model="form.newPassword"
            :input-attrs="{ id: 'reset-password', autocomplete: 'new-password' }" /></a-form-item
      ></template>
    </a-form>
    <template #footer>
      <a-button :disabled="saving" @click="close">取消</a-button>
      <a-button
        data-test="confirm-action"
        type="primary"
        :status="mode === 'status' && selected?.status === 'active' ? 'danger' : 'normal'"
        :loading="saving"
        @click="submitDialog"
        >{{ mode === 'create' ? '提交' : mode === 'reset' ? '确认重置' : '确认' + title }}</a-button
      >
    </template>
  </a-modal>
</template>
