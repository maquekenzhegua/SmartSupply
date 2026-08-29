<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between"><span>供应商</span><el-button type="primary" @click="openDialog()">新增供应商</el-button></div>
    </template>
    <el-form inline>
      <el-form-item><el-input v-model="keyword" placeholder="供应商名称" clearable /></el-form-item>
      <el-form-item><el-button @click="load">搜索</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="contact_name" label="联系人" width="120" />
      <el-table-column prop="contact_phone" label="电话" width="140" />
      <el-table-column prop="email" label="邮箱" width="170" />
      <el-table-column prop="rating" label="评分" width="90" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="160"><template #default="{ row }"><el-button size="small" @click="openDialog(row)">编辑</el-button><el-button size="small" type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    <el-dialog v-model="visible" :title="form.id ? '编辑供应商' : '新增供应商'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.contactPhone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="评分"><el-input v-model="form.rating" type="number" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0), keyword = ref('')
const visible = ref(false)
const form = ref<Record<string, unknown>>({ name: '', contactName: '', contactPhone: '', email: '', address: '', rating: 4.5 })
function openDialog(row?: Record<string, unknown>) {
  if (row) form.value = { id: row.id, name: row.name, contactName: row.contact_name, contactPhone: row.contact_phone, email: row.email, address: row.address, rating: row.rating }
  else form.value = { name: '', contactName: '', contactPhone: '', email: '', address: '', rating: 4.5 }
  visible.value = true
}
async function load() {
  loading.value = true
  try { const d = await api.suppliers({ page: page.value, size: size.value, keyword: keyword.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  if (!String(form.value.name || '').trim()) return ElMessage.warning('名称不能为空')
  if (form.value.id) await api.updateSupplier(form.value.id as number, form.value)
  else await api.createSupplier(form.value)
  ElMessage.success('保存成功'); visible.value = false; load()
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该供应商？','提示')
  await api.deleteSupplier(row.id as number); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
