<template>
  <el-card>
    <template #header><div style="display:flex; justify-content:space-between"><span>Prompt 管理</span><el-button type="primary" @click="dialog=true">新建版本</el-button></div></template>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="agent_type" label="类型" width="120" />
      <el-table-column prop="version" label="版本" width="140" />
      <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column prop="active" label="激活" width="80"><template #default="{row}">{{ row.active?'✅':'' }}</template></el-table-column>
      <el-table-column prop="created_by" label="创建人" width="100" />
      <el-table-column prop="created_at" label="时间" width="170" />
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" @click="activate(row)" :disabled="row.active">激活</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialog" title="新建 Prompt 版本" width="600px">
      <el-form label-width="80px"><el-form-item label="类型"><el-select v-model="form.agentType"><el-option label="general" value="general" /><el-option label="contract" value="contract" /><el-option label="replenishment" value="replenishment" /><el-option label="bi" value="bi" /></el-select></el-form-item><el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="8" /></el-form-item></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
const rows=ref<any[]>([]), loading=ref(false), dialog=ref(false)
const form=ref({agentType:'general', content:''})
async function load(){ loading.value=true; try{ const d=await api.adminPrompts(); rows.value=Array.isArray(d)?d:d.rows||[] } finally{ loading.value=false } }
async function submit(){ if(!form.value.content.trim()) return ElMessage.warning('内容不能为空'); await api.adminCreatePrompt({agentType: form.value.agentType, content: form.value.content}); ElMessage.success('已创建'); dialog.value=false; load() }
async function activate(row:any){ await api.adminActivatePrompt(row.id); ElMessage.success('已激活'); load() }
onMounted(load)
</script>
