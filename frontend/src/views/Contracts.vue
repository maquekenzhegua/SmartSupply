<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>合同管理</span>
          <div style="display:flex; gap:8px">
            <el-button @click="openCreate">新建合同</el-button>
            <el-upload :before-upload="beforeUpload" :show-file-list="false" accept=".pdf,.txt,.docx"><el-button type="primary">上传合同</el-button></el-upload>
          </div>
        </div>
      </template>
      <el-alert type="info" :closable="false" style="margin-bottom: 12px" title="支持传统新建/编辑，也支持上传文件触发 RAG 风控分析" />
      <el-table :data="rows" v-loading="loading" @row-click="onRowClick">
        <el-table-column prop="title" label="合同" min-width="220" />
        <el-table-column prop="supplier_name" label="供应商" width="140" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="amount" label="金额" width="120" />
        <el-table-column prop="created_at" label="创建时间" width="170" />
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" @click.stop="viewReport(row)">风控报告</el-button>
            <el-button size="small" @click.stop="openCreate(row)">编辑</el-button>
            <el-button size="small" type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:12px; justify-content:flex-end" v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑合同' : '新建合同'" width="500px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="供应商ID"><el-input v-model="form.supplierId" type="number" /></el-form-item>
        <el-form-item label="金额"><el-input v-model="form.amount" type="number" /></el-form-item>
        <el-form-item label="签订日期"><el-input v-model="form.signDate" type="date" placeholder="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="DRAFT" value="DRAFT" /><el-option label="REVIEWING" value="REVIEWING" /><el-option label="ACTIVE" value="ACTIVE" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="formVisible=false">取消</el-button><el-button type="primary" @click="submit">确定</el-button></template>
    </el-dialog>

    <el-dialog v-model="reportVisible" title="风控报告" width="720px">
      <div v-loading="reportLoading" style="white-space: pre-wrap; line-height: 1.7">{{ reportText || '暂无报告' }}</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import request from '@/utils/request'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0)
const reportVisible = ref(false), reportLoading = ref(false), reportText = ref('')
const formVisible = ref(false)
const form = ref<Record<string, unknown>>({ title: '', supplierId: '', amount: 0, signDate: '', status: 'DRAFT' })
function openCreate(row?: Record<string, unknown>) {
  if (row) form.value = { id: row.id, title: row.title, supplierId: row.supplier_id, amount: row.amount, signDate: row.sign_date, status: row.status }
  else form.value = { title: '', supplierId: '', amount: 0, signDate: '', status: 'DRAFT' }
  formVisible.value = true
}
async function load() {
  loading.value = true
  try { const d = await api.contracts({ page: page.value, size: size.value }); rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function submit() {
  if (!String(form.value.title || '').trim()) return ElMessage.warning('标题不能为空')
  if (form.value.id) await request.put(`/contracts-extra/${form.value.id}`, form.value)
  else await request.post('/contracts-extra', form.value)
  ElMessage.success('保存成功'); formVisible.value = false; load()
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该合同？','提示')
  await request.delete(`/contracts-extra/${row.id}`); ElMessage.success('已删除'); load()
}
async function beforeUpload(file: File) {
  try {
    ElMessage.info('正在解析与风控分析…')
    const res = await api.uploadContract(file)
    ElMessage.success('分析完成')
    reportText.value = res.report || '已生成报告'
    reportVisible.value = true
    load()
  } catch { ElMessage.error('上传失败，请检查后端') }
  return false
}
async function viewReport(row: Record<string, unknown>) {
  reportVisible.value = true; reportLoading.value = true
  try { const r = await api.riskReport(row.id as number); reportText.value = (r.summary as string) || JSON.stringify(r, null, 2) }
  catch { reportText.value = '暂无风控报告，先上传一份合同触发分析。' }
  finally { reportLoading.value = false }
}
function onRowClick(row: Record<string, unknown>) { viewReport(row) }
onMounted(load)
</script>
