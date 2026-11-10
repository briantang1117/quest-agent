<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { createMaterialsApi, notifyError, useAuthStore, type Material } from '@quest/shared'
import MaterialCard from '../components/MaterialCard.vue'
import { useMaterialPolling } from '../composables/material-polling'
const api = createMaterialsApi(useAuthStore())
const materials = ref<Material[]>([]),
  loading = ref(false),
  failed = ref(false)
let alive = true
onBeforeUnmount(() => {
  alive = false
})
function update(value: Material) {
  materials.value = materials.value.map((item) => (item.id === value.id ? value : item))
}
const { paused, resume } = useMaterialPolling(() => materials.value, update)
async function load() {
  if (loading.value) return
  loading.value = true
  failed.value = false
  try {
    const value = await api.list()
    if (!alive) return
    materials.value = value.list
    resume()
  } catch (cause) {
    if (alive) {
      failed.value = true
      notifyError(cause)
    }
  } finally {
    if (alive) loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <section class="learning-materials">
    <header class="materials-heading">
      <div>
        <h1>学习资料</h1>
        <p>从对话中整理，留给下一次复习。</p>
      </div>
      <a-button :loading="loading" @click="load">刷新</a-button>
    </header>
    <a-alert v-if="paused" type="warning" class="materials-notice"
      >状态更新已暂停，请刷新后继续查看。</a-alert
    >
    <a-spin :loading="loading" style="width: 100%">
      <a-empty v-if="failed" description="学习资料加载失败，请刷新重试" />
      <a-empty v-else-if="!loading && !materials.length" description="还没有学习资料"
        ><RouterLink class="text-link" to="/practice/agent">去对话中整理</RouterLink></a-empty
      >
      <div class="materials-list">
        <MaterialCard
          v-for="material in materials"
          :key="material.id"
          :material="material"
          @update="update"
        />
      </div>
    </a-spin>
  </section>
</template>
<style scoped>
.learning-materials {
  width: 100%;
  min-width: 0;
}
.materials-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.materials-heading h1 {
  font-size: 24px;
  margin: 0 0 8px;
}
.materials-heading p {
  color: var(--color-text-3);
  margin: 0;
  font-size: 13px;
}
.materials-list {
  display: grid;
  gap: 16px;
}
.materials-notice {
  margin-bottom: 16px;
}
</style>
