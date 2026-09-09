<template>
  <div class="agent-page">
    <AgentChat :default-agent="defaultAgent" :initial-query="initialQuery" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AgentChat from '@/components/AgentChat.vue'
const route = useRoute()
const defaultAgent = computed(() => (route.query.agent as string) || 'general')
const initialQuery = computed(() => (route.query.q as string) || '')
</script>

<style scoped>
/* 占满顶栏以下全部空间，对话流内部自己滚动（60 顶栏 + 48 上下内边距） */
.agent-page {
  height: calc(100vh - 108px);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>
