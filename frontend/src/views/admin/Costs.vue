<template>
  <el-card>
    <template #header><span>成本看板</span></template>
    <el-form inline>
      <el-form-item><el-select v-model="groupBy" style="width:140px"><el-option label="按天" value="day" /><el-option label="按类型" value="agentType" /><el-option label="按用户" value="user" /></el-select></el-form-item>
      <el-form-item><el-button @click="load">刷新</el-button></el-form-item>
    </el-form>
    <div ref="chartRef" style="height:340px"></div>
    <el-table :data="rows" style="margin-top:12px">
      <el-table-column prop="grp" label="分组" />
      <el-table-column prop="cnt" label="次数" />
      <el-table-column prop="tokens" label="tokens" />
      <el-table-column prop="cost" label="cost" />
    </el-table>
  </el-card>
</template>
<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import * as echarts from 'echarts'
import { api } from '@/api'
const groupBy=ref('day'), rows=ref<any[]>([]), chartRef=ref<HTMLElement>()
let chart:any=null
async function load(){ const d=await api.adminCosts({groupBy: groupBy.value}); rows.value=d.rows||[]; render() }
function render(){
  if(!chartRef.value) return
  if(!chart) chart=echarts.init(chartRef.value)
  chart.setOption({ tooltip:{trigger:'axis'}, xAxis:{type:'category', data: rows.value.map((r:any)=>String(r.grp))}, yAxis:{type:'value'}, series:[{type:'bar', data: rows.value.map((r:any)=>Number(r.cost)||0), name:'cost'}, {type:'line', data: rows.value.map((r:any)=>Number(r.tokens)||0), name:'tokens'}] })
}
watch(groupBy, load)
onMounted(load)
</script>
