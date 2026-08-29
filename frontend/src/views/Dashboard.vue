<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><el-statistic title="供应商" :value="summary.supplierCount" /></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="商品/SKU" :value="(summary.productCount as number) + '/' + summary.skuCount" /></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="低于安全库存" :value="summary.lowStockCount"><template #suffix><el-tag v-if="(summary.lowStockCount as number)>0" type="danger" size="small" style="margin-left: 8px">需补货</el-tag></template></el-statistic></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="采购单/合同" :value="(summary.poCount as number) + '/' + summary.contractCount" /></el-card></el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card header="分类库存分布（传统BI）">
          <v-chart :option="catOption" autoresize style="height: 320px" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card header="低库存预警">
          <el-table :data="lowStockWarn" size="small"><el-table-column prop="sku_code" label="SKU" /><el-table-column prop="quantity" label="库存" /><el-table-column prop="safety_stock" label="安全库存" /></el-table>
          <el-button style="margin-top: 12px" @click="$router.push('/inventory')">去库存管理</el-button>
          <el-button style="margin-top: 12px" @click="$router.push('/inventory-flows')">出入库流水</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { api } from '@/api'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { PieChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const summary = ref<Record<string, unknown>>({ supplierCount: 0, productCount: 0, skuCount: 0, lowStockCount: 0, poCount: 0, contractCount: 0 })
const catRows = ref<Record<string, unknown>[]>([])
const lowStockWarn = ref<Record<string, unknown>[]>([])

const catOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{ type: 'pie', radius: ['40%', '70%'], data: catRows.value.map(r => ({ name: String(r.category), value: Number(r.total_qty) })) }],
}))

onMounted(async () => {
  try {
    const s = await api.statsSummary(); summary.value = s
    const cats = await api.categoryStock(); catRows.value = cats as Record<string, unknown>[]
    const inv = await api.inventory({ page: 1, size: 5 }); lowStockWarn.value = (inv.records || []).filter((r: Record<string, unknown>) => r.below_safety)
  } catch {}
})
</script>
