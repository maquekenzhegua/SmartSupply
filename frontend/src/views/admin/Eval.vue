<template>
  <div class="page">
    <div class="page-header">
      <div class="titles">
        <h1 class="page-title">评测看板</h1>
        <p class="page-desc">运行总量、反馈分布、低分候选与评测快照趋势</p>
      </div>
      <div class="actions">
        <el-button :icon="Download" @click="exportCandidates">导出候选 JSONL</el-button>
      </div>
    </div>

    <el-card class="list-card" shadow="never">
      <div class="eval-stat">
        <div class="eval-stat-icon"><el-icon :size="18"><Monitor /></el-icon></div>
        <div>
          <div class="eval-stat-value">{{ data.totalRuns }}</div>
          <div class="eval-stat-label">总运行次数</div>
        </div>
      </div>
      <el-table :data="feedbackRows" class="eval-table">
        <el-table-column prop="rating" label="评分（1 赞 / -1 踩）" />
        <el-table-column prop="cnt" label="数量" />
      </el-table>
    </el-card>

    <el-card class="list-card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>评测候选（低分反馈回流）</span>
          <span class="head-note">人工补 ground_truth 后并入 golden_rag.jsonl 即完成在线→离线回流</span>
        </div>
      </template>
      <el-table v-loading="loadingCandidates" :data="candidates">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="rating" label="评分" width="70" />
        <el-table-column prop="question" label="用户提问" min-width="220" show-overflow-tooltip />
        <el-table-column prop="comment" label="反馈说明" min-width="160" show-overflow-tooltip />
        <el-table-column prop="agentType" label="类型" width="110" />
        <el-table-column prop="mode" label="模式" width="150" />
        <el-table-column prop="promptVersion" label="Prompt 版本" width="110" />
        <el-table-column prop="runId" label="Run" width="80" />
      </el-table>
    </el-card>

    <el-card class="list-card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head"><span>评测快照趋势</span><span class="head-note">llm_judge.py --snapshot-url 回传；离线报告的时序留痕</span></div>
      </template>
      <el-table v-loading="loadingSnapshots" :data="snapshots">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="source" label="来源" width="150" />
        <el-table-column label="avg_keyword_hit" width="140">
          <template #default="{ row }">{{ formatMetric(row.metrics, 'avg_keyword_hit') }}</template>
        </el-table-column>
        <el-table-column label="avg_faithfulness" width="140">
          <template #default="{ row }">{{ formatMetric(row.metrics, 'avg_faithfulness') }}</template>
        </el-table-column>
        <el-table-column label="avg_tool_f1" width="120">
          <template #default="{ row }">{{ formatMetric(row.metrics, 'avg_tool_f1') }}</template>
        </el-table-column>
        <el-table-column prop="reportFile" label="报告" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-alert class="eval-tip" title="详细报告见 docs/eval-report-*.md（离线 mock 为自证基线；真实模型报告需配置 Key 后运行 scripts/llm_judge.py --mode real 生成）" type="info" :closable="false" />
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { Download, Monitor } from '@element-plus/icons-vue'
const data = ref<any>({ totalRuns: 0, feedback: [] })
const feedbackRows = ref<any[]>([])
const candidates = ref<any[]>([])
const snapshots = ref<any[]>([])
const loadingCandidates = ref(false)
const loadingSnapshots = ref(false)

function formatMetric(m: any, key: string): string {
  if (m && typeof m === 'object' && m[key] !== undefined && m[key] !== null) return String(m[key])
  return '—'
}

async function load() {
  const d = await api.adminEval(); data.value = d; feedbackRows.value = d.feedback || []
  loadingCandidates.value = true
  try { candidates.value = await api.adminEvalCandidates({ maxRating: 0, limit: 50 }) } finally { loadingCandidates.value = false }
  loadingSnapshots.value = true
  try { snapshots.value = await api.adminEvalSnapshots({ limit: 50 }) } finally { loadingSnapshots.value = false }
}

async function exportCandidates() {
  try {
    const blob = await api.adminExportEvalCandidates({ maxRating: 0, limit: 200 })
    const url = URL.createObjectURL(new Blob([blob], { type: 'application/x-ndjson' }))
    const a = document.createElement('a')
    a.href = url; a.download = 'eval-candidates.jsonl'; a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出（ground_truth 留空，需人工标注后再并入 golden 集）')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  }
}

onMounted(load)
</script>

<style scoped>
.eval-stat {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.eval-stat-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--brand-50);
  color: var(--brand-600);
}

.eval-stat-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.eval-stat-label {
  font-size: 12.5px;
  color: var(--ink-500);
}

.eval-table {
  width: 320px;
}

.eval-tip {
  margin-top: 16px;
  border-radius: var(--radius-md);
}

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
