<script setup lang="ts">
import DepthRouterView from '../components/DepthRouterView.vue'
import { useCacheScope } from '../use-cache-scope'
const cacheScope = useCacheScope()
</script>

<template>
  <div class="practice-layout">
    <main id="main" class="page-container"><DepthRouterView :key="cacheScope" /></main>
  </div>
</template>
