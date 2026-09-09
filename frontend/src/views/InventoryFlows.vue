<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">库存流水</h1>
        <p class="page-desc">每一次出入库变动的完整留痕</p>
      </div>
      <div class="actions">
        <el-button :icon="EditPen" @click="openAdjust">出入库调整</el-button>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="skuCode" placeholder="按SKU编码过滤" clearable style="width: 240px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="load">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="sku_code" label="SKU" width="160" />
        <el-table-column prop="warehouse_name" label="仓库" width="130" />
        <el-table-column prop="change_qty" label="变动" width="100"><template #default="{ row }"><el-tag :type="(row.change_qty as number) > 0 ? 'success' : 'danger'" effect="light" round>{{ row.change_qty > 0 ? '+' + row.change_qty : row.change_qty }}</el-tag></template></el-table-column>
        <el-table-column prop="reason" label="原因" min-width="200" />
        <el-table-column prop="created_at" label="时间" width="170" />
      </el-table>
      <el-pagination v-model:current-page="page" class="pager" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>
    <el-dialog v-model="visible" title="库存调整" width="420px">
      <el-form label-width="90px">
        <el-form-item label="SKU ID"><el-input v-model="form.skuId" type="number" /></el-form-item>
        <el-form-item label="仓库ID"><el-input v-model="form.warehouseId" type="number" /></el-form-item>
        <el-form-item label="数量"><el-input v-model="form.changeQty" type="number" placeholder="正数入库/负数出库" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { EditPen } from '@element-plus/icons-vue'
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
