<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">库存管理</h1>
        <p class="page-desc">实时库存与安全库存预警 · 点击「AI建议」触发补货 Agent</p>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="sku_code" label="SKU" width="160" />
        <el-table-column prop="product_name" label="商品" />
        <el-table-column prop="spec" label="规格" width="120" />
        <el-table-column prop="warehouse" label="仓库" width="120" />
        <el-table-column prop="quantity" label="库存" width="90" />
        <el-table-column prop="safety_stock" label="安全库存" width="100" />
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="row.below_safety ? 'danger' : 'success'" effect="light" round>{{ row.below_safety ? '低于安全库存' : '充足' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button size="small" type="primary" plain :icon="MagicStick" @click="advise(row)">AI建议</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" class="pager" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>

    <el-dialog v-model="dialogVisible" title="补货 Agent 建议" width="560px">
      <div class="advise-text">{{ adviseText }}</div>
      <template #footer><el-button @click="dialogVisible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/api'
import { MagicStick } from '@element-plus/icons-vue'
const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const dialogVisible = ref(false)
const adviseText = ref('')

async function load() {
  loading.value = true
  try {
    const data = await api.inventory({ page: page.value, size: size.value })
    rows.value = data.records || data.rows || []
    total.value = data.total || 0
  } finally { loading.value = false }
}
async function advise(row: Record<string, unknown>) {
  adviseText.value = '思考中…'
  dialogVisible.value = true
  try {
    const res = await api.agentChat({ message: `SKU ${row.sku_code} 当前库存${row.quantity}，安全库存${row.safety_stock}，是否需要补货？请给出建议，必要时调用工具。`, agentType: 'replenishment' })
    adviseText.value = res.reply
  } catch { adviseText.value = '调用失败，请检查后端。' }
}
onMounted(load)
</script>

<style scoped>
.advise-text {
  white-space: pre-wrap;
  line-height: 1.7;
  color: var(--ink-700);
}
</style>
