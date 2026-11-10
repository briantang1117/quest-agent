<script setup lang="ts">
import type { SyllabusMetadata } from '@quest/shared'
defineProps<{ prefix?: string }>()
const model = defineModel<SyllabusMetadata>('model', { required: true })
const required = [{ required: true, whitespace: true, message: '请填写此项' }]
const sourceRule = [
  {
    validator: (value: string, callback: (error?: string) => void) => {
      if (!value) return callback()
      try {
        const url = new URL(value)
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
          throw new Error()
        callback()
      } catch {
        callback('请输入无账号密码的 http/https 来源网址')
      }
    },
  },
]
</script>
<template>
  <a-form-item field="title" label="标题" :rules="required" :data-test="`${prefix}-title`"
    ><a-input v-model="model.title"
  /></a-form-item>
  <a-form-item
    field="versionLabel"
    label="版本标签"
    :rules="required"
    :data-test="`${prefix}-versionLabel`"
    ><a-input v-model="model.versionLabel"
  /></a-form-item>
  <a-form-item field="issuer" label="发布机构" :rules="required" :data-test="`${prefix}-issuer`"
    ><a-input v-model="model.issuer"
  /></a-form-item>
  <a-form-item field="publishedDate" label="发布日期"
    ><a-date-picker
      :model-value="model.publishedDate ?? undefined"
      value-format="YYYY-MM-DD"
      allow-clear
      @update:model-value="
        model.publishedDate = typeof $event === 'string' && $event ? $event : null
      "
  /></a-form-item>
  <a-form-item field="sourceUrl" label="来源网址" :rules="sourceRule"
    ><a-input v-model="model.sourceUrl" allow-clear
  /></a-form-item>
</template>
