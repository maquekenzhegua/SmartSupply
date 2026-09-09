<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">成本看板</h1>
        <p class="page-desc">Token 用量与成本台账（真实 / 估算双口径）</p>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <div class="toolbar">
        <el-select v-model="groupBy" style="width:140px"><el-option label="按天" value="day" /><el-option label="按类型" value="agentType" /><el-option label="按用户" value="user" /></el-select>
        <el-button @click="load">刷新</el-button>
      </div>
      <div ref="chartRef" class="cost-chart"></div>
      <el-table :data="rows" class="cost-table">
        <el-table-column prop="grp" label="分组" />
        <el-table-column prop="cnt" label="次数" />
        <el-table-column prop="tokens" label="tokens" />
        <el-table-column prop="cost" label="cost" />
      </el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import * as echarts from 'echarts'
import { api } from '@/api'
import { chartBase, chartPalette } from '@/utils/chartTheme'
const groupBy=ref('day'), rows=ref<any[]>([]), chartRef=ref<HTMLElement>()
let chart:any=null
async function load(){ const d=await api.adminCosts({groupBy: groupBy.value}); rows.value=d.rows||[]; render() }
function render(){
  if(!chartRef.value) return
  if(!chart) chart=echarts.init(chartRef.value)
  chart.setOption({ ...chartBase(), tooltip:{ ...chartBase().tooltip, trigger:'axis' }, xAxis:{type:'category', data: rows.value.map((r:any)=>String(r.grp))}, yAxis:{type:'value', splitLine:{lineStyle:{color:'#f1f5f9'}}}, series:[{type:'bar', data: rows.value.map((r:any)=>Number(r.cost)||0), name:'cost', itemStyle:{color: chartPalette[0], borderRadius:[4,4,0,0]}}, {type:'line', data: rows.value.map((r:any)=>Number(r.tokens)||0), name:'tokens', itemStyle:{color: chartPalette[1]}, smooth:true}] })
}
watch(groupBy, load)
onMounted(load)
</script>

<style scoped>
.cost-chart {
  height: 340px;
}

.cost-table {
  margin-top: 16px;
}
</style>
