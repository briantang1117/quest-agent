<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  createUserApi,
  errorMessage,
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
const error = ref('')
const success = ref('')
const modalError = ref('')
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
  error.value = ''
  data.value = null
  try {
    const result = await api.list({ ...query })
    if (alive && version === listRevision) data.value = result
  } catch (cause) {
    if (alive && version === listRevision) error.value = errorMessage(cause)
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
  modalError.value = ''
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
  modalError.value = ''
}
async function save() {
  if (saving.value || !mode.value) return
  saving.value = true
  modalError.value = ''
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
    if (alive) modalError.value = errorMessage(cause)
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
  <section class="page-heading">
    <div>
      <p class="breadcrumb">管理工作台 / 账号</p>
      <h1>用户管理</h1>
      <p class="lead">管理普通用户的访问资格，不触及用户的私有学习内容。</p>
    </div>
    <a-button data-test="create-user" type="primary" :disabled="saving" @click="open('create')"
      >创建用户</a-button
    >
  </section>
  <a-alert v-if="success" type="success" role="status">{{ success }}</a-alert>
  <section class="panel user-panel" aria-label="普通用户列表">
    <form data-test="filter" class="user-filters" @submit.prevent="filter">
      <div>
        <label for="user-keyword">用户名或展示名</label
        ><a-input
          v-model="filters.keyword"
          :input-attrs="{ id: 'user-keyword' }"
          placeholder="输入关键词"
          allow-clear
        />
      </div>
      <div>
        <label for="user-status">账号状态</label
        ><a-select v-model="filters.status" input-id="user-status" aria-label="账号状态"
          ><a-option value="">全部状态</a-option><a-option value="active">启用</a-option
          ><a-option value="disabled">禁用</a-option></a-select
        >
      </div>
      <a-button html-type="submit" :disabled="loading || saving" type="primary">筛选</a-button>
      <a-button data-test="refresh-users" :loading="loading" :disabled="saving" @click="load"
        >刷新列表</a-button
      >
    </form>
    <p class="table-note">
      只列出普通用户账号。时间按北京时间显示；不展示私有题库、题目、附件或对话。
    </p>
    <a-alert v-if="error" type="error" role="alert"
      >列表加载失败：{{ error }}。可点击刷新列表重试。</a-alert
    >
    <a-table
      v-if="!error"
      row-key="id"
      :data="data?.list ?? []"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1000 }"
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
  </section>
  <p class="privacy-note">
    账号管理与学习工作区相互隔离。使用量统计尚未接入，本页不提供内容预览或模拟登录。
  </p>
  <a-modal
    :visible="mode !== null"
    :title="title"
    title-align="start"
    :footer="false"
    :width="520"
    :mask-closable="!saving"
    :closable="!saving"
    :esc-to-close="!saving"
    unmount-on-close
    @cancel="close"
  >
    <p v-if="selected" class="modal-account">
      {{ selected.displayName }}（{{ selected.username }}）
    </p>
    <a-alert v-if="modalError" type="error" role="alert">{{ modalError }}</a-alert>
    <template v-if="mode === 'status'">
      <p>
        {{
          selected?.status === 'active'
            ? '禁用后将立即撤销已有会话，用户无法继续登录。账号及其数据不会被删除。'
            : '启用后用户可使用现有密码重新登录，不会恢复已撤销的会话。'
        }}
      </p>
      <div class="dialog-actions">
        <a-button :disabled="saving" @click="close">取消</a-button
        ><a-button
          data-test="confirm-action"
          type="primary"
          :status="selected?.status === 'active' ? 'danger' : 'normal'"
          :loading="saving"
          @click="save"
          >确认{{ title }}</a-button
        >
      </div>
    </template>
    <a-form
      v-else
      :key="mode ?? 'closed'"
      data-test="user-form"
      :model="form"
      layout="vertical"
      :disabled="saving"
      @submit-success="save"
    >
      <template v-if="mode === 'create'">
        <p>仅创建普通用户，不创建管理员。初始密码由你设置，请通过安全渠道单独交付。</p>
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
      <div class="dialog-actions">
        <a-button :disabled="saving" @click="close">取消</a-button
        ><a-button html-type="submit" type="primary" :loading="saving">{{
          mode === 'create' ? '创建用户' : '确认重置密码'
        }}</a-button>
      </div>
    </a-form>
  </a-modal>
</template>
