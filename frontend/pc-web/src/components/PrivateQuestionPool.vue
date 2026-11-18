<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  createPrivateQuestionsApi,
  notifyError,
  subjectLabel,
  useAuthStore,
  type PrivateQuestion,
} from '@quest/shared'
import PrivateQuestionContent from './PrivateQuestionContent.vue'
const api = createPrivateQuestionsApi(useAuthStore())
const filters = reactive({ subject: '', status: '', q: '', page: 1 })
const data = ref<{ list: PrivateQuestion[]; total: number; page: number; size: number } | null>(
    null,
  ),
  loading = ref(false),
  failed = ref(false)
let alive = true,
  revision = 0
onBeforeUnmount(() => {
  alive = false
  revision++
})
async function load(page = 1) {
  const version = ++revision
  filters.page = page
  loading.value = true
  failed.value = false
  try {
    const result = await api.list({ ...filters })
    if (alive && version === revision) data.value = result
  } catch (error) {
    if (alive && version === revision) {
      failed.value = true
      data.value = null
      notifyError(error)
    }
  } finally {
    if (alive && version === revision) loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <p class="pool-hint">
      每人一个私有题目池。向 Agent 发送截图、PDF
      或文本，再说“记下这道题”或“帮我导入错题”，全程在对话里完成。
    </p>
    <a-form :model="filters" layout="inline" @submit-success="load()">
      <a-form-item label="科目"
        ><a-select v-model="filters.subject" placeholder="全部科目" style="width: 180px"
          ><a-option value="">全部科目</a-option
          ><a-option value="vocational_aptitude">事业编 A 类 · 职测</a-option
          ><a-option value="administrative_aptitude">上海市考 · 行测</a-option></a-select
        ></a-form-item
      >
      <a-form-item label="状态"
        ><a-select v-model="filters.status" style="width: 150px"
          ><a-option value="">全部状态</a-option><a-option value="ready">已收录</a-option
          ><a-option value="needs_attention">待核实</a-option
          ><a-option value="excluded">已排除练习</a-option></a-select
        ></a-form-item
      >
      <a-form-item label="搜索"
        ><a-input v-model="filters.q" allow-clear :max-length="100" /></a-form-item
      ><a-button type="primary" html-type="submit" :loading="loading">查询</a-button>
    </a-form>
    <a-divider />
    <a-spin :loading="loading" style="width: 100%">
      <a-empty v-if="failed" description="私有题库加载失败，请确认服务及数据库升级后重试" />
      <a-empty v-else-if="!loading && !data?.list.length" description="还没有符合条件的私有题目"
        ><RouterLink to="/practice/agent">去 Agent 导入</RouterLink></a-empty
      >
      <a-card
        v-for="question in data?.list ?? []"
        :key="question.id"
        class="pool-question"
        :title="`${subjectLabel(question.subject)} · ${question.kind === 'mistake' ? '错题' : '题目'}`"
      >
        <template #extra
          ><a-tag>{{
            question.status === 'ready'
              ? '已收录'
              : question.status === 'excluded'
                ? '已排除练习'
                : '待核实'
          }}</a-tag></template
        >
        <PrivateQuestionContent
          :result="question.result"
          show-conversation
          :show-issues="question.status === 'needs_attention'"
        />
      </a-card>
    </a-spin>
    <a-pagination
      v-if="data"
      :total="data.total"
      :current="data.page"
      :page-size="20"
      show-total
      @change="load"
    />
  </div>
</template>
<style scoped>
.pool-hint {
  color: var(--color-text-3);
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 20px;
}
.pool-question {
  margin-bottom: 16px;
  border-radius: 2px;
}
</style>
