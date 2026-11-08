<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DepthRouterView from '../components/DepthRouterView.vue'
import { useCacheScope } from '../composables/cache-scope'
const cacheScope = useCacheScope()
const route = useRoute()
// Agent 页需要在顶栏与footer之间占满一屏，其他页保持自然文档流。
const fillViewport = computed(() => route.name === 'AgentView')
</script>

<template>
  <div class="practice-layout">
    <main id="main" class="page-container" :class="{ 'agent-fill': fillViewport }">
      <DepthRouterView :key="cacheScope" />
    </main>
  </div>
</template>

<style scoped>
/* 学员端整屏：内容区吃掉顶栏与footer之间的剩余高度，页面级不滚动。 */
.practice-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.page-container {
  flex: 1;
  min-height: 0;
}
</style>
