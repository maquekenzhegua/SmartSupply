<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">Prompt 管理</h1>
        <p class="page-desc">各 Agent 类型的 Prompt 版本管理与激活切换</p>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="dialog=true">新建版本</el-button>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="agent_type" label="类型" width="120" />
        <el-table-column prop="version" label="版本" width="140" />
        <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
        <el-table-column prop="active" label="激活" width="90"><template #default="{row}"><el-tag v-if="row.active" type="success" effect="light" round>ACTIVE</el-tag></template></el-table-column>
        <el-table-column prop="created_by" label="创建人" width="100" />
        <el-table-column prop="created_at" label="时间" width="170" />
        <el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" :disabled="row.active" @click="activate(row)">激活</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-card class="list-card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>生效提示词（运行时真实注入对话的版本）</span>
          <span class="head-note">db=治理表生效；builtin=内置默认（无激活覆盖）。发布/激活后下一次对话立即生效</span>
        </div>
      </template>
      <el-table v-loading="loadingEffective" :data="effectiveRows">
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="version" label="生效版本" width="120" />
        <el-table-column prop="source" label="来源" width="100">
          <template #default="{ row }">
            <el-tag :type="row.source === 'db' ? 'success' : 'info'" effect="light" round>{{ row.source }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialog" title="新建 Prompt 版本" width="600px">
      <el-form label-width="80px"><el-form-item label="类型"><el-select v-model="form.agentType"><el-option label="general" value="general" /><el-option label="contract" value="contract" /><el-option label="replenishment" value="replenishment" /><el-option label="bi" value="bi" /></el-select></el-form-item><el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="8" /></el-form-item></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="submit">提交</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { Plus } from '@element-plus/icons-vue'
const rows=ref<any[]>([]), loading=ref(false), dialog=ref(false)
const effectiveRows=ref<any[]>([]), loadingEffective=ref(false)
const form=ref({agentType:'general', content:''})
async function load(){
  loading.value=true; loadingEffective.value=true
  try{
    const d=await api.adminPrompts(); rows.value=Array.isArray(d)?d:d.rows||[]
    const eff=await api.adminPromptsEffective()
    effectiveRows.value=Object.entries(eff||{}).map(([type, v]: any) => ({ type, version: v.version, source: v.source }))
  } finally{ loading.value=false; loadingEffective.value=false }
}
async function submit(){ if(!form.value.content.trim()) return ElMessage.warning('内容不能为空'); await api.adminCreatePrompt({agentType: form.value.agentType, content: form.value.content}); ElMessage.success('已创建'); dialog.value=false; load() }
async function activate(row:any){ await api.adminActivatePrompt(row.id); ElMessage.success('已激活（下一次对话生效）'); load() }
onMounted(load)
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.head-note {
  font-size: 12px;
  color: var(--ink-500);
}
</style>
