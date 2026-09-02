<template>
  <el-card>
    <template #header><span>评测看板</span></template>
    <div>总运行次数: {{ data.totalRuns }}</div>
    <el-table :data="feedbackRows" style="margin-top:12px">
      <el-table-column prop="rating" label="评分" />
      <el-table-column prop="cnt" label="数量" />
    </el-table>
    <el-alert title="详细报告见 docs/eval-report-*.md（mock 基线 0.90，真实模型待全量跑）" type="info" style="margin-top:12px" :closable="false" />
  </el-card>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/api'
const data=ref<any>({totalRuns:0, feedback:[]}), feedbackRows=ref<any[]>([])
onMounted(async()=>{ const d=await api.adminEval(); data.value=d; feedbackRows.value=d.feedback||[] })
</script>
