<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>SKU 管理</span><el-button type="primary" @click="openDialog()">新增SKU</el-button>
        </div>
      </template>
      <el-form inline>
        <el-form-item><el-input v-model="keyword" placeholder="SKU编码" clearable /></el-form-item>
        <el-form-item><el-button @click="load">搜索</el-button></el-form-item>
      </el-form>
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="sku_code" label="SKU编码" width="170" />
        <el-table-column prop="product_name" label="商品" width="140" />
        <el-table-column prop="spec" label="规格" width="120" />
        <el-table-column prop="cost_price" label="成本价" width="100" />
        <el-table-column prop="sale_price" label="售价" width="100" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }"><el-button size="small" @click="openDialog(row)">编辑</el-button><el-button size="small" type="danger" @click="remove(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 12px; justify-content: flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>
    <el-dialog v-model="visible" :title="form.id ? '编辑SKU' : '新增SKU'" width="500px">
      <el-form label-width="90px">
        <el-form-item label="商品ID"><el-input v-model="form.productId" type="number" /></el-form-item>
        <el-form-item label="SKU编码"><el-input v-model="form.skuCode" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item>
        <el-form-item label="成本价"><el-input v-model="form.costPrice" type="number" /></el-form-item>
        <el-form-item label="售价"><el-input v-model="form.salePrice" type="number" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0), keyword = ref('')
const visible = ref(false)
const form = ref<Record<string, unknown>>({ productId: '', skuCode: '', spec: '', costPrice: 0, salePrice: 0 })
function openDialog(row?: Record<string, unknown>) {
  if (row) form.value = { id: row.id, productId: row.product_id, skuCode: row.sku_code, spec: row.spec, costPrice: row.cost_price, salePrice: row.sale_price }
  else form.value = { productId: '', skuCode: '', spec: '', costPrice: 0, salePrice: 0 }
  visible.value = true
}
async function load() {
  loading.value = true
  try { const d = await api.skus({ page: page.value, size: size.value, keyword: keyword.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  if (!form.value.skuCode || !form.value.productId) return ElMessage.warning('商品ID与SKU编码必填')
  if (form.value.id) await api.updateSku(form.value.id as number, form.value)
  else await api.createSku(form.value)
  ElMessage.success('保存成功'); visible.value = false; load()
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该SKU？', '提示')
  await api.deleteSku(row.id as number); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
