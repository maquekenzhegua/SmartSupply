<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>商品管理</span>
          <el-button type="primary" @click="openDialog()">新增商品</el-button>
        </div>
      </template>
      <el-form inline @submit.prevent>
        <el-form-item><el-input v-model="keyword" placeholder="商品名搜索" clearable @keyup.enter="load" /></el-form-item>
        <el-form-item><el-button @click="load">搜索</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="name" label="商品" min-width="160" />
        <el-table-column prop="category" label="分类" width="100" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column prop="bar_code" label="条码" width="140" />
        <el-table-column prop="sku_count" label="SKU数" width="90" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" style="margin-top: 12px; justify-content: flex-end" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="form.category" placeholder="服装/箱包/日用" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" /></el-form-item>
        <el-form-item label="条码"><el-input v-model="form.barCode" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0), keyword = ref('')
const dialogVisible = ref(false)
const form = ref<Record<string, unknown>>({ name: '', category: '', unit: '件', barCode: '' })
function openDialog(row?: Record<string, unknown>) {
  if (row) form.value = { id: row.id, name: row.name, category: row.category, unit: row.unit, barCode: row.bar_code }
  else form.value = { name: '', category: '', unit: '件', barCode: '' }
  dialogVisible.value = true
}
async function load() {
  loading.value = true
  try { const d = await api.products({ page: page.value, size: size.value, keyword: keyword.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  if (!String(form.value.name || '').trim()) return ElMessage.warning('名称不能为空')
  try {
    if (form.value.id) await api.updateProduct(form.value.id as number, { name: form.value.name, category: form.value.category, unit: form.value.unit, barCode: form.value.barCode })
    else await api.createProduct({ name: form.value.name, category: form.value.category, unit: form.value.unit, barCode: form.value.barCode })
    ElMessage.success('保存成功'); dialogVisible.value = false; load()
  } catch {}
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该商品？', '提示')
  await api.deleteProduct(row.id as number); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
