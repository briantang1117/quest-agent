<script setup lang="ts">
import { Message } from '@arco-design/web-vue'
import { IconRefresh } from '@arco-design/web-vue/es/icon'
import { onMounted, onBeforeUnmount, reactive, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  createAdminControlApi,
  notifyError,
  useAuthStore,
  type UserControls,
  type UsageRow,
} from '@quest/shared'
const route = useRoute()
const id = String(route.params.id)
const api = createAdminControlApi(useAuthStore())
const data = ref<UserControls | null>(null)
const loadFailed = ref(false)
const busy = ref(false)
const loading = ref(false)
const credit = reactive({ amount: 100000, reason: '' })
const requestId = ref(crypto.randomUUID())
const reconciling = ref<UsageRow | null>(null)
const reconciliation = reactive({ inputTokens: 0, outputTokens: 0, reason: '' })
function openReconciliation(row: UsageRow) {
  reconciling.value = row
  reconciliation.inputTokens = 0
  reconciliation.outputTokens = 0
  reconciliation.reason = ''
}
const labels: Record<string, string> = {
  coach: '对话与刷题',
  syllabus: '考纲导览',
  search: '联网搜索与网页抓取',
  materials: '高级功能：整理学习资料（Markdown / PDF）',
}
const total = computed(() =>
  data.value?.models.reduce(
    (r, m) => ({
      credited: r.credited + Number(m.credited),
      consumed: r.consumed + Number(m.consumed),
      reserved: r.reserved + Number(m.reserved),
      available: r.available + Number(m.available),
    }),
    { credited: 0, consumed: 0, reserved: 0, available: 0 },
  ),
)
let alive = true
let revision = 0
onBeforeUnmount(() => {
  alive = false
  revision++
})
async function load() {
  const version = ++revision
  loadFailed.value = false
  loading.value = true
  try {
    const result = await api.controls(id)
    if (alive && version === revision) data.value = result
  } catch (e) {
    if (alive && version === revision) {
      loadFailed.value = true
      notifyError(e)
    }
  } finally {
    if (alive && version === revision) loading.value = false
  }
}
async function action(operation: () => Promise<unknown>, text: string) {
  if (busy.value) return
  busy.value = true
  try {
    await operation()
    if (!alive) return
    Message.success(text)
    await load()
  } catch (e) {
    if (alive) notifyError(e)
  } finally {
    if (alive) busy.value = false
  }
}
async function topup() {
  if (
    !Number.isSafeInteger(credit.amount) ||
    credit.amount <= 0 ||
    credit.amount > 1_000_000_000_000
  ) {
    Message.warning('请输入 1 至 1,000,000,000,000 的整数 Token 数量')
    return
  }
  await action(async () => {
    await api.credit(id, { ...credit, requestId: requestId.value })
    requestId.value = crypto.randomUUID()
    credit.reason = ''
  }, '充值成功')
}
async function reconcile() {
  const row = reconciling.value
  if (!row) return
  if (!reconciliation.reason.trim()) {
    Message.warning('请填写核对依据或原因')
    return
  }
  await action(async () => {
    await api.reconcile(id, row.id, { ...reconciliation })
    reconciling.value = null
  }, '已按核对结果结算')
}
onMounted(load)
</script>
<template>
  <a-card
    class="admin-users-card"
    :title="`用户授权与额度 · ${route.query.name || id}`"
    :bordered="false"
  >
    <div class="user-toolbar">
      <span class="controls-hint">用户 ID：{{ id }}</span>
      <a-button :loading="loading" :disabled="busy" @click="load"
        ><template #icon><IconRefresh /></template>刷新</a-button
      >
    </div>
    <section class="controls-section" aria-labelledby="session-controls-title">
      <h2 id="session-controls-title" class="user-toolbar-title">会话管理</h2>
      <div class="session-controls">
        <p>强制下线会撤销此用户全部登录会话和续期资格，账号仍可重新登录。</p>
        <a-popconfirm
          content="确认让此用户的全部设备下线？"
          @ok="action(() => api.kick(id), '已有会话已撤销')"
          ><a-button status="danger" :disabled="busy || loading || loadFailed || !data"
            >强制下线</a-button
          ></a-popconfirm
        >
      </div>
    </section>
    <a-divider />
    <a-alert v-if="loadFailed" type="error" style="margin-bottom: 20px"
      >用户授权与额度加载失败，请稍后重试。</a-alert
    >
    <template v-if="data && !loadFailed">
      <section class="controls-section" aria-labelledby="feature-controls-title">
        <h2 id="feature-controls-title" class="user-toolbar-title">功能开放</h2>
        <div>
          <div v-for="(enabled, feature) in data.features" :key="feature" class="feature-row">
            <span>{{ labels[feature] || feature }}</span
            ><a-switch
              :data-feature="feature"
              :model-value="enabled"
              :disabled="busy"
              @change="
                (value: string | number | boolean) =>
                  action(() => api.feature(id, feature, Boolean(value)), '功能配置已更新')
              "
            />
          </div>
        </div>
      </section>
      <a-divider />
      <section class="controls-section" aria-labelledby="balance-controls-title">
        <h2 id="balance-controls-title" class="user-toolbar-title">累计 Token 额度</h2>
        <p class="controls-hint">
          所有模型共用此额度，输入与输出 Token 合计扣减，额度由后台手动充值。
        </p>
        <div v-if="total" class="balance-summary">
          <div>
            <span>累计充值</span><strong>{{ total.credited.toLocaleString() }}</strong>
          </div>
          <div>
            <span>已消费</span><strong>{{ total.consumed.toLocaleString() }}</strong>
          </div>
          <div>
            <span>预占 / 待核对</span><strong>{{ total.reserved.toLocaleString() }}</strong>
          </div>
          <div>
            <span>可用 Token</span><strong>{{ total.available.toLocaleString() }}</strong>
          </div>
        </div>
        <a-form
          :model="credit"
          layout="vertical"
          class="credit-form"
          :disabled="busy || loading"
          @submit-success="topup"
          ><a-row :gutter="16"
            ><a-col :xs="24" :md="12"
              ><a-form-item label="充值 Token 数量" field="amount"
                ><a-input-number
                  v-model="credit.amount"
                  :min="1"
                  :max="1000000000000"
                  :precision="0" /></a-form-item></a-col
            ><a-col :xs="24" :md="12"
              ><a-form-item label="充值原因（选填）" field="reason"
                ><a-input v-model="credit.reason" :max-length="500" /></a-form-item></a-col
          ></a-row>
          <div class="credit-actions">
            <a-button type="primary" html-type="submit" :loading="busy">充值</a-button>
          </div></a-form
        >
      </section>
      <a-divider />
      <section class="controls-section" aria-labelledby="usage-controls-title">
        <h2 id="usage-controls-title" class="user-toolbar-title">最近模型调用</h2>
        <p class="controls-hint">
          预占不会按估算值扣费。供应商缺少usage或断流时，保留额度待管理员核对；核对为未计费时可填0结算。
        </p>
        <a-table :data="data.usage" :pagination="false" row-key="id" :scroll="{ x: 1000 }"
          ><template #columns
            ><a-table-column
              title="模型"
              data-index="model"
              :width="240"
              ellipsis
              tooltip
            /><a-table-column title="能力" data-index="capability" /><a-table-column
              title="状态"
              data-index="status"
            /><a-table-column
              title="输入"
              data-index="input_tokens"
              align="right"
              :width="130"
            /><a-table-column
              title="输出"
              data-index="output_tokens"
              align="right"
              :width="130"
            /><a-table-column
              title="预占"
              data-index="reservation"
              align="right"
              :width="130"
            /><a-table-column title="操作"
              ><template #cell="{ record }"
                ><a-button
                  v-if="record.status !== 'settled'"
                  size="small"
                  @click="openReconciliation(record)"
                  >核对结算</a-button
                ></template
              ></a-table-column
            ></template
          ><template #empty
            ><a-empty description="暂无模型计量记录；启用计量后的新调用会在此显示"
          /></template>
        </a-table>
      </section>
    </template>
    <a-modal
      :visible="Boolean(reconciling)"
      title="人工核对 token 用量"
      :mask-closable="false"
      :ok-loading="busy"
      @cancel="reconciling = null"
      @ok="reconcile"
      ><p>请先核对供应商账单，不要仅因页面失败就将用量记零。仍在执行中的请求不能人工结算。</p>
      <a-form :model="reconciliation" layout="vertical"
        ><a-form-item label="输入token"
          ><a-input-number
            v-model="reconciliation.inputTokens"
            :min="0"
            :precision="0" /></a-form-item
        ><a-form-item label="输出token"
          ><a-input-number
            v-model="reconciliation.outputTokens"
            :min="0"
            :precision="0" /></a-form-item
        ><a-form-item label="核对依据 / 原因"
          ><a-input v-model="reconciliation.reason" :max-length="500" /></a-form-item></a-form
    ></a-modal>
  </a-card>
</template>
<style scoped>
.controls-section h2 {
  margin: 0 0 12px;
}
.controls-hint {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.7;
}
.session-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.feature-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px solid var(--color-border-2);
}
.feature-row:last-child {
  border-bottom: 0;
}
.balance-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 16px 0;
  padding: 20px;
  background: var(--color-fill-1);
  border-radius: 2px;
}
.balance-summary span {
  display: block;
  margin-bottom: 8px;
  color: var(--color-text-3);
  font-size: 12px;
}
.balance-summary strong {
  font-size: 24px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.credit-form {
  margin-top: 20px;
}
.credit-actions {
  display: flex;
  justify-content: flex-end;
}
p {
  color: var(--color-text-2);
  line-height: 1.7;
}
@media (max-width: 767px) {
  .balance-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .session-controls {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
