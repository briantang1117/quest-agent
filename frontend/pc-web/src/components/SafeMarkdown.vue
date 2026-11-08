<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@quest/shared'
const props = defineProps<{ source: string | null | undefined }>()
// renderMarkdown已关闭原始HTML并经DOMPurify清洗；v-html只在此组件内出现。
const html = computed(() => renderMarkdown(props.source ?? ''))
</script>
<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="safe-markdown" v-html="html" />
</template>
