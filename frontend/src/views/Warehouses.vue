<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">仓库管理</h1>
        <p class="page-desc">仓库主数据与库位地址</p>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增仓库</el-button>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="name" label="仓库" min-width="160" />
        <el-table-column prop="location" label="地址" min-width="200" />
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button size="small" @click="openDialog(row)">编辑</el-button><el-button size="small" type="danger" plain @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" class="pager" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>
    <el-dialog v-model="visible" :title="form.id ? '编辑仓库' : '新增仓库'" width="420px">
      <el-form label-width="80px"><el-form-item label="名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="地址"><el-input v-model="form.location" /></el-form-item></el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import { Plus } from '@element-plus/icons-vue'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0)
const visible = ref(false)
const form = ref<Record<string, unknown>>({ name: '', location: '' })
function openDialog(row?: Record<string, unknown>) {
  if (row) form.value = { id: row.id, name: row.name, location: row.location }
  else form.value = { name: '', location: '' }
  visible.value = true
}
async function load() {
  loading.value = true
  try { const d = await api.warehouses({ page: page.value, size: size.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  if (!String(form.value.name || '').trim()) return ElMessage.warning('名称不能为空')
  if (form.value.id) await api.updateWarehouse(form.value.id as number, form.value)
  else await api.createWarehouse(form.value)
  ElMessage.success('保存成功'); visible.value = false; load()
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该仓库？', '提示')
  await api.deleteWarehouse(row.id as number); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
