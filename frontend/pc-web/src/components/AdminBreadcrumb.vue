<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconLeft } from '@arco-design/web-vue/es/icon'

const props = defineProps<{
  items: { label: string; to?: string }[]
  backTo?: string
}>()
const router = useRouter(),
  route = useRoute()
const showBack = computed(() => (route.meta.pageDeepth ?? 1) > 1)
function goBack() {
  const previous = router.options.history.state.back
  if (typeof previous === 'string' && previous.startsWith('/admin/')) {
    const target = router.resolve(previous)
    const depth = target.meta.pageDeepth
    if (typeof depth === 'number' && depth < (route.meta.pageDeepth ?? 1)) {
      router.back()
      return
    }
  }
  void router.push(props.backTo ?? '/admin/users')
}
</script>
<template>
  <nav class="admin-breadcrumb breadcrumb-bar" aria-label="面包屑">
    <a-link v-if="showBack" class="breadcrumb-back" data-test="breadcrumb-back" @click="goBack">
      <template #icon><IconLeft /></template>返回
    </a-link>
    <a-breadcrumb>
      <a-breadcrumb-item v-for="(item, index) in items" :key="index">
        <RouterLink v-if="item.to && index < items.length - 1" :to="item.to">{{
          item.label
        }}</RouterLink>
        <span v-else>{{ item.label }}</span>
      </a-breadcrumb-item>
    </a-breadcrumb>
  </nav>
</template>
<style scoped>
.breadcrumb-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.breadcrumb-back {
  flex: none;
  color: var(--color-text-3);
  font-size: 13px;
}
.breadcrumb-back:hover {
  color: rgb(var(--primary-6));
}
:deep(.arco-breadcrumb-item a) {
  color: var(--color-text-3);
  text-decoration: none;
}
:deep(.arco-breadcrumb-item a:hover) {
  color: rgb(var(--primary-6));
}
</style>
