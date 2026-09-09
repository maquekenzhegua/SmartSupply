<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">仪表盘</h1>
        <p class="page-desc">供应链运行总览 · 数据每 5 分钟随业务刷新</p>
      </div>
    </div>

    <!-- 统计卡：图标磁贴 + 大数字 -->
    <el-row :gutter="16">
      <el-col v-for="s in statCards" :key="s.label" :xs="12" :sm="12" :md="6">
        <el-card class="stat-card hover-lift" shadow="never">
          <el-skeleton v-if="loading" :rows="1" animated class="stat-skeleton" />
          <template v-else>
            <div class="stat-top">
              <div class="stat-icon" :style="{ background: s.tile, color: s.tint }">
                <el-icon :size="18"><component :is="s.icon" /></el-icon>
              </div>
              <span class="stat-label">{{ s.label }}</span>
            </div>
            <div class="stat-row">
              <span class="stat-value">{{ s.value }}</span>
              <el-tag v-if="s.tag" type="danger" size="small" effect="light" round>{{ s.tag }}</el-tag>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>分类库存分布</span>
              <span class="card-head-sub">传统 BI · 各品类库存量占比</span>
            </div>
          </template>
          <el-skeleton v-if="loading" :rows="6" animated />
          <el-empty v-else-if="!catRows.length" description="暂无库存数据" :image-size="88" class="chart-empty" />
          <v-chart v-else :option="catOption" autoresize class="cat-chart" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="warn-card">
          <template #header>
            <div class="card-head">
              <span>低库存预警</span>
              <el-tag v-if="!loading && lowStockWarn.length" type="danger" size="small" effect="light" round>
                {{ lowStockWarn.length }}
              </el-tag>
            </div>
          </template>
          <el-skeleton v-if="loading" :rows="5" animated />
          <template v-else>
            <el-empty v-if="!lowStockWarn.length" description="库存充足，暂无预警" :image-size="72" />
            <el-table v-else :data="lowStockWarn" size="small" class="warn-table">
              <el-table-column prop="sku_code" label="SKU" min-width="120" />
              <el-table-column prop="quantity" label="库存" width="70" />
              <el-table-column prop="safety_stock" label="安全库存" width="86" />
            </el-table>
            <div class="warn-actions">
              <el-button type="primary" plain size="small" @click="$router.push('/inventory')">
                <el-icon class="btn-icon"><Box /></el-icon>去库存管理
              </el-button>
              <el-button size="small" @click="$router.push('/inventory-flows')">
                <el-icon class="btn-icon"><List /></el-icon>出入库流水
              </el-button>
            </div>
          </template>
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
import { chartBase } from '@/utils/chartTheme'
use([PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const summary = ref<Record<string, unknown>>({ supplierCount: 0, productCount: 0, skuCount: 0, lowStockCount: 0, poCount: 0, contractCount: 0 })
const catRows = ref<Record<string, unknown>[]>([])
const lowStockWarn = ref<Record<string, unknown>[]>([])
const loading = ref(true)

// 统计卡配置：图标磁贴底色与主数并列，需补货随数量出现
const statCards = computed(() => [
  { label: '供应商', icon: 'OfficeBuilding', tile: 'var(--brand-50)', tint: 'var(--brand-600)', value: summary.value.supplierCount, tag: '' },
  { label: '商品 / SKU', icon: 'Goods', tile: '#e0f2fe', tint: '#0284c7', value: `${summary.value.productCount} / ${summary.value.skuCount}`, tag: '' },
  { label: '低于安全库存', icon: 'WarningFilled', tile: '#fef2f2', tint: 'var(--danger)', value: summary.value.lowStockCount, tag: Number(summary.value.lowStockCount) > 0 ? '需补货' : '' },
  { label: '采购单 / 合同', icon: 'ShoppingCart', tile: '#ecfdf5', tint: 'var(--success)', value: `${summary.value.poCount} / ${summary.value.contractCount}`, tag: '' },
])

const catOption = computed(() => ({
  ...chartBase(),
  tooltip: { ...chartBase().tooltip, trigger: 'item' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: '#64748b', fontSize: 12 } },
  series: [{
    type: 'pie',
    radius: ['52%', '74%'],
    center: ['50%', '44%'],
    itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 6 },
    label: { show: false },
    emphasis: { scaleSize: 6 },
    data: catRows.value.map(r => ({ name: String(r.category), value: Number(r.total_qty) })),
  }],
}))

onMounted(async () => {
  // 三个独立数据源并行拉取（此前串行 await，白首屏时间三倍叠加）
  try {
    const [s, cats, inv] = await Promise.all([
      api.statsSummary(),
      api.categoryStock(),
      api.inventory({ page: 1, size: 5 }),
    ])
    summary.value = s
    catRows.value = cats as Record<string, unknown>[]
    lowStockWarn.value = (inv.records || []).filter((r: Record<string, unknown>) => r.below_safety)
  } catch (e) {
    // 每个请求失败时 axios 拦截器已 toast 具体原因；这里补日志避免整块静默空白无法归因
    console.warn('[dashboard] 数据加载失败（图表区可能显示为空）', e)
  } finally { loading.value = false }
})
</script>

<style scoped>
.stat-card :deep(.el-card__body) {
  padding: 18px 20px;
}

.stat-skeleton {
  width: 120px;
}

.stat-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stat-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
}

.stat-label {
  font-size: 13px;
  color: var(--ink-500);
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.chart-row {
  margin-top: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-400);
}

.cat-chart {
  height: 320px;
}

.chart-empty {
  height: 320px;
  display: grid;
  place-items: center;
}

.warn-card {
  height: 100%;
}

.warn-table {
  width: 100%;
}

.warn-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.btn-icon {
  margin-right: 4px;
}
</style>
