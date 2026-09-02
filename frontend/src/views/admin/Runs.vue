<template>
  <el-card>
    <template #header><span>Agent Runs — Trace 回放</span></template>
    <el-form inline>
      <el-form-item><el-input v-model="q.user" placeholder="用户" clearable style="width:120px" /></el-form-item>
      <el-form-item><el-input v-model="q.sessionId" placeholder="sessionId" clearable style="width:160px" /></el-form-item>
      <el-form-item><el-select v-model="q.mode" placeholder="mode" clearable style="width:120px"><el-option label="java-direct" value="java-direct" /><el-option label="python-deep" value="python-deep" /></el-select></el-form-item>
      <el-form-item><el-button @click="load">查询</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading" @row-click="openDetail">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="trace_id" label="Trace" width="160" />
      <el-table-column prop="username" label="用户" width="100" />
      <el-table-column prop="agent_type" label="类型" width="100" />
      <el-table-column prop="mode" label="mode" width="110" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column prop="latency_ms" label="耗时ms" width="90" />
      <el-table-column prop="total_tokens" label="tokens" width="90" />
      <el-table-column prop="cost_usd" label="cost" width="100" />
      <el-table-column prop="created_at" label="时间" width="170" />
    </el-table>
    <el-pagination style="margin-top:12px; justify-content:flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    <el-dialog v-model="detailVisible" title="Trace 详情" width="700px">
      <div v-if="detail">
        <div>Trace: {{ detail.trace_id }} | 耗时 {{ detail.latency_ms }}ms | tokens {{ detail.total_tokens }} | {{ detail.token_source }}</div>
        <el-timeline style="margin-top:12px">
          <el-timeline-item v-for="s in detail.steps||[]" :key="s.id" :timestamp="s.node+':'+s.name">{{ s.input_digest }} → {{ s.output_digest }} ({{ s.latency_ms }}ms)</el-timeline-item>
        </el-timeline>
        <div v-for="t in detail.toolCalls||[]" :key="t.id" style="background:#fafafa; padding:6px; margin:4px 0; border-radius:6px; font-size:12px">
          <b>{{ t.tool }}</b> {{ t.args_json }} → {{ t.result_digest }} ({{ t.success ? 'ok':'fail' }})
        </div>
      </div>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/api'
const rows=ref<any[]>([]), loading=ref(false), page=ref(1), size=ref(20), total=ref(0)
const q=ref({user:'', sessionId:'', mode:''})
const detail=ref<any>(null), detailVisible=ref(false)
async function load(){ loading.value=true; try{ const d=await api.adminRuns({page:page.value, size:size.value, user:q.value.user||undefined, sessionId:q.value.sessionId||undefined, mode:q.value.mode||undefined}); rows.value=d.records||d.rows||[]; total.value=d.total||0 } finally{ loading.value=false } }
async function openDetail(row:any){ const d=await api.adminRunDetail(row.id); detail.value=d; detailVisible.value=true }
onMounted(load)
</script>
