<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FormInstance } from '@arco-design/web-vue'
import type { SyllabusExamType, SyllabusSection } from '@quest/shared'
const props = defineProps<{
  section: SyllabusSection | null
  examType: SyllabusExamType
  pageCount: number
  ordinals: number[]
}>()
const emit = defineEmits<{ close: []; apply: [section: SyllabusSection] }>()
const form = ref<FormInstance>(),
  draft = ref<SyllabusSection | null>(null)
watch(
  () => props.section,
  (value) => {
    draft.value = value ? { ...value } : null
  },
  { immediate: true },
)
const supported = computed(
  () =>
    !!draft.value &&
    ['overview', 'requirement'].includes(draft.value.kind) &&
    draft.value.scope ===
      (props.examType === 'career_a' ? 'vocational_aptitude' : 'administrative_aptitude'),
)
watch(supported, (value) => {
  if (!value && draft.value) draft.value.enabled = false
})
const error = ref('')
async function apply() {
  if (!draft.value || !form.value || (await form.value.validate())) return
  const value = draft.value
  if (
    !Number.isInteger(value.ordinal) ||
    value.ordinal < 1 ||
    props.ordinals.includes(value.ordinal)
  ) {
    error.value = '章节序号须为不重复的正整数'
    return
  }
  if (
    ![value.pageStart, value.pageEnd].every(
      (page) => Number.isInteger(page) && page >= 1 && page <= props.pageCount,
    ) ||
    value.pageStart > value.pageEnd
  ) {
    error.value = '物理页码须在原件范围内，起始页不得大于结束页'
    return
  }
  error.value = ''
  emit('apply', { ...value, enabled: supported.value && value.enabled })
}
const required = [{ required: true, whitespace: true, message: '请填写此项' }]
</script>
<template>
  <a-modal
    :visible="section !== null"
    title="编辑章节"
    title-align="start"
    modal-class="user-dialog"
    :width="760"
    :mask-closable="false"
    unmount-on-close
    @cancel="emit('close')"
  >
    <a-form v-if="draft" ref="form" :model="draft" auto-label-width @submit-success="apply">
      <a-form-item label="序号"
        ><a-input-number v-model="draft.ordinal" :min="1" :precision="0"
      /></a-form-item>
      <a-form-item field="title" label="章节标题" :rules="required"
        ><a-input v-model="draft.title"
      /></a-form-item>
      <a-form-item field="path" label="章节路径" :rules="required"
        ><a-input v-model="draft.path"
      /></a-form-item>
      <a-form-item label="类型"
        ><a-select v-model="draft.kind"
          ><a-option value="overview">概述</a-option><a-option value="requirement">要求</a-option
          ><a-option value="example">例题</a-option
          ><a-option value="other">其他</a-option></a-select
        ></a-form-item
      >
      <a-form-item label="适用范围"
        ><a-select v-model="draft.scope"
          ><a-option value="vocational_aptitude">事业编 A 类 · 职测</a-option
          ><a-option value="administrative_aptitude">上海市考 · 行测</a-option
          ><a-option value="excluded">排除（仅保留原文）</a-option></a-select
        ></a-form-item
      >
      <a-form-item
        label="启用状态"
        :extra="
          supported
            ? '须逐章人工审核后再启用。'
            : '仅本考试范围的概述或要求可启用，例题和其他内容只保留原文。'
        "
        ><a-switch
          v-model="draft.enabled"
          :disabled="!supported"
          checked-text="启用"
          unchecked-text="停用"
      /></a-form-item>
      <a-form-item label="起始物理页"
        ><a-input-number v-model="draft.pageStart" :min="1" :max="pageCount" :precision="0"
      /></a-form-item>
      <a-form-item label="结束物理页"
        ><a-input-number v-model="draft.pageEnd" :min="1" :max="pageCount" :precision="0"
      /></a-form-item>
      <a-form-item label="印刷页码"
        ><a-input v-model="draft.printedPages" allow-clear
      /></a-form-item>
      <a-form-item field="text" label="章节原文" :rules="required"
        ><a-textarea v-model="draft.text" :auto-size="{ minRows: 6, maxRows: 16 }"
      /></a-form-item> </a-form
    ><a-alert v-if="error" type="error">{{ error }}</a-alert>
    <template #footer
      ><a-button @click="emit('close')">取消</a-button
      ><a-button data-test="apply-section" type="primary" @click="apply"
        >应用到草稿</a-button
      ></template
    >
  </a-modal>
</template>
