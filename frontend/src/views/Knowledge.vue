<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">知识库（RAG）</h1>
        <p class="page-desc">文档入库、向量检索与召回质量测试</p>
      </div>
      <div class="actions">
        <el-button :icon="EditPen" @click="textDialog=true">手动录入</el-button>
        <el-upload :before-upload="beforeUpload" :show-file-list="false" accept=".pdf,.txt,.docx"><el-button type="primary" :icon="Upload">上传文档</el-button></el-upload>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="recallQuery" placeholder="输入问题测试召回，如：无限连带责任" style="width: 360px" clearable @keyup.enter="doRecall" />
        <el-button type="primary" plain @click="doRecall">召回测试</el-button>
      </div>
      <div v-if="recallCtx" class="recall-box">{{ recallCtx }}</div>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="source_type" label="来源" width="110" />
        <el-table-column prop="content_len" label="长度" width="90" />
        <el-table-column prop="created_at" label="创建时间" width="170" />
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" type="danger" plain @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" class="pager" :page-size="size" :total="total" layout="prev, pager, next" @current-change="load" />
    </el-card>

    <el-dialog v-model="textDialog" title="手动录入" width="520px">
      <el-form label-width="80px"><el-form-item label="标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="6" /></el-form-item></el-form>
      <template #footer><el-button @click="textDialog=false">取消</el-button><el-button type="primary" @click="submitText">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { EditPen, Upload } from '@element-plus/icons-vue'
const rows = ref<Record<string, unknown>[]>([]), loading = ref(false)
const page = ref(1), size = ref(10), total = ref(0)
const recallQuery = ref(''), recallCtx = ref('')
const textDialog = ref(false)
const form = ref({ title: '', content: '' })
async function load() {
  loading.value = true
  try { const r = await request.get('/knowledge', { params: { page: page.value, size: size.value } }); const d = r.data.data || r.data; rows.value = d.records || []; total.value = d.total || 0 }
  finally { loading.value = false }
}
async function beforeUpload(file: File) {
  const fd = new FormData(); fd.append('file', file)
  try { await request.post('/knowledge/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success('已入库'); load() } catch (e) { console.warn('[knowledge] 上传入库失败（拦截器已提示）', e) }
  return false
}
async function submitText() {
  if (!form.value.content.trim()) return ElMessage.warning('内容不能为空')
  await request.post('/knowledge/text', form.value); ElMessage.success('已入库'); textDialog.value = false; load()
}
async function doRecall() {
  const r = await request.post('/knowledge/recall', { query: recallQuery.value }); recallCtx.value = (r.data.data || r.data).context || ''
}
async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm('确认删除该知识？','提示')
  await request.delete(`/knowledge/${row.id}`); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>

<style scoped>
.recall-box {
  white-space: pre-wrap;
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  color: var(--ink-700);
  padding: 12px 14px;
  border-radius: var(--radius-md);
  margin-bottom: 14px;
  font-size: 13px;
  line-height: 1.7;
}
</style>
