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
      <div v-loading="loading" class="chart-wrap">
        <!-- vue-echarts：随组件卸载自动 dispose、autoresize 监听窗口——此前手动
             echarts.init 后永不释放（切页签即泄漏）且全量引入 echarts（与 Dashboard
             的按需注册形成双份打包） -->
        <VChart class="cost-chart" :option="option" autoresize />
        <el-empty v-if="!loading && !rows.length" description="暂无台账数据" :image-size="72" />
      </div>
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
import { ref, computed, watch, onMounted } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '@/api'
import { chartBase, chartPalette } from '@/utils/chartTheme'
use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const groupBy = ref('day')
const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const d = await api.adminCosts({ groupBy: groupBy.value })
    rows.value = d.rows || []
  } catch (e) {
    // 此前无 loading 态也无 catch：接口挂了整个看板静默空白无法归因
    console.warn('[costs] 成本台账加载失败（拦截器已提示具体原因）', e)
  } finally {
    loading.value = false
  }
}

const option = computed(() => ({
  ...chartBase(),
  tooltip: { ...chartBase().tooltip, trigger: 'axis' },
  xAxis: { type: 'category', data: rows.value.map((r: any) => String(r.grp)) },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [
    { type: 'bar', data: rows.value.map((r: any) => Number(r.cost) || 0), name: 'cost',
      itemStyle: { color: chartPalette[0], borderRadius: [4, 4, 0, 0] } },
    { type: 'line', data: rows.value.map((r: any) => Number(r.tokens) || 0), name: 'tokens',
      itemStyle: { color: chartPalette[1] }, smooth: true },
  ],
}))

watch(groupBy, load)
onMounted(load)
</script>

<style scoped>
.chart-wrap {
  position: relative;
  min-height: 340px;
}

.cost-chart {
  height: 340px;
}

.cost-table {
  margin-top: 16px;
}
</style>
