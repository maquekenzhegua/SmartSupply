<template>
  <div style="height: calc(100vh - 140px); display: flex; flex-direction: column">
    <el-alert type="success" :closable="false" style="margin-bottom: 12px" title="Agent 工作台：选 Agent 类型对话，支持查库存/下采购单/搜合同与商品，多轮记忆与流式打字机。全局 Ask 搜框的内容会自动带入。" />
    <el-card style="flex: 1; display: flex; flex-direction: column; overflow: hidden">
      <AgentChat :default-agent="defaultAgent" :initial-query="initialQuery" style="flex: 1; min-height: 0" />
    </el-card>
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
