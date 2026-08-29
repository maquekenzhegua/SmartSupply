<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between"><span>库存流水</span><el-button @click="openAdjust">出入库调整</el-button></div>
    </template>
    <el-form inline>
      <el-form-item><el-input v-model="skuCode" placeholder="按SKU编码过滤" clearable /></el-form-item>
      <el-form-item><el-button @click="load">查询</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="sku_code" label="SKU" width="160" />
      <el-table-column prop="warehouse_name" label="仓库" width="130" />
      <el-table-column prop="change_qty" label="变动" width="100"><template #default="{ row }"><el-tag :type="(row.change_qty as number) > 0 ? 'success' : 'danger'">{{ row.change_qty }}</el-tag></template></el-table-column>
      <el-table-column prop="reason" label="原因" min-width="200" />
      <el-table-column prop="created_at" label="时间" width="170" />
    </el-table>
    <el-pagination style="margin-top:12px; justify-content:flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    <el-dialog v-model="visible" title="库存调整" width="420px">
      <el-form label-width="90px">
        <el-form-item label="SKU ID"><el-input v-model="form.skuId" type="number" /></el-form-item>
        <el-form-item label="仓库ID"><el-input v-model="form.warehouseId" type="number" /></el-form-item>
        <el-form-item label="数量"><el-input v-model="form.changeQty" type="number" placeholder="正数入库/负数出库" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0), skuCode = ref('')
const visible = ref(false)
const form = ref<Record<string, unknown>>({ skuId: '', warehouseId: '', changeQty: 0, reason: 'MANUAL' })
function openAdjust() { visible.value = true }
async function load() {
  loading.value = true
  try { const d = await api.inventoryFlows({ page: page.value, size: size.value, skuCode: skuCode.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  await api.adjustInventory(form.value); ElMessage.success('调整成功'); visible.value = false; load()
}
onMounted(load)
</script>
