<template>
  <div class="agent-chat">
    <div class="messages" ref="msgRef">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="role">{{ m.role === 'user' ? '你' : (m.agentType || 'Agent') }}</div>
        <div v-if="m.role === 'assistant' && m.tools && m.tools.length" class="tool-chips">
          <el-tag v-for="(tool, ti) in m.tools" :key="ti" type="warning" effect="plain" size="small" style="margin-right:4px">
            🔧 {{ tool.split('(')[0] }}
          </el-tag>
        </div>
        <div v-if="m.role === 'assistant'" class="bubble md" v-html="renderMd(m.content)"></div>
        <div v-else class="bubble">{{ m.content }}</div>
        <div v-if="m.role === 'assistant' && m.citations && m.citations.length" class="citations">
          <div style="font-size:12px; color:#909399; margin-top:4px">引用</div>
          <div v-for="c in m.citations" :key="c.idx" style="font-size:12px; background:#fafafa; padding:4px 8px; border-radius:6px; margin:4px 0">
            <b>[{{ c.idx }}] {{ c.title }}</b> <span style="color:#909399">score={{ Number(c.score).toFixed(3) }}</span><br/>{{ c.snippet }}
          </div>
        </div>
        <div v-if="m.role === 'assistant'" style="display:flex; gap:6px; margin-top:4px">
          <el-button size="small" @click="feedback(i, 1)">👍</el-button>
          <el-button size="small" @click="feedback(i, -1)">👎</el-button>
        </div>
      </div>
      <div v-if="loading" class="msg assistant"><div class="bubble">思考中…<span v-if="streamText">{{ streamText.slice(0, 40) }}…</span></div></div>
    </div>
    <div class="tool-bar" v-if="toolList.length">
      <span style="color:#909399; font-size:12px">可用工具：</span>
      <el-tag v-for="t in toolList" :key="t.name" size="small" style="margin-left:6px">{{ t.name }}</el-tag>
    </div>
    <div class="input-row">
      <el-select v-model="agentType" style="width: 160px" size="large">
        <el-option label="通用助手" value="general" />
        <el-option label="合同风控" value="contract" />
        <el-option label="补货预测" value="replenishment" />
        <el-option label="经营分析" value="bi" />
      </el-select>
      <el-input v-model="input" :placeholder="placeholderText" size="large" @keyup.enter="send" />
      <el-button size="small" @click="clearSession">清空记忆</el-button>
      <el-switch v-model="useStream" active-text="流式" style="margin-left:6px" />
      <el-switch v-model="useDeep" active-text="深度推理" :disabled="!deepEnabled" :title="deepEnabled ? '走 Python LangGraph 边车' : '需启动 agent-python 且 Java 开启 AGENT_PYTHON_ENABLED'" style="margin-left:6px" />
      <el-button type="primary" size="large" :loading="loading" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, computed, watch } from 'vue'
import { marked } from 'marked'
import { api } from '@/api'
import request from '@/utils/request'

marked.setOptions({ breaks: true, gfm: true })
// 先转义 HTML 再解析 markdown：保留 markdown 语法的同时中和模型输出里的原始 HTML 标签
function renderMd(src: string): string {
  return marked.parse((src || '').replace(/</g, '&lt;'), { async: false }) as string
}

const props = withDefaults(defineProps<{ defaultAgent?: string; initialQuery?: string }>(), {
  defaultAgent: 'general',
  initialQuery: '',
})
const agentType = ref(props.defaultAgent)
const input = ref(props.initialQuery)
const loading = ref(false)
const useStream = ref(true)
const useDeep = ref(false)
const deepEnabled = ref(false)
const streamText = ref('')
const sessionId = ref('sess-' + Math.random().toString(36).slice(2, 8))
const toolList = ref<{ name: string; desc: string }[]>([])
const messages = ref<{ role: 'user' | 'assistant'; content: string; agentType?: string; tools?: string[]; citations?: any[]; runId?: number }[]>([
  { role: 'assistant', content: '你好，我是 SmartSupply Agent，可查库存、创建采购单、搜合同/商品，支持多轮记忆。试试：“哪些SKU低于安全库存？”或“帮我查一下T恤的SKU”。', agentType: 'Agent' },
])
const msgRef = ref<HTMLElement>()

const placeholderText = computed(() => {
  switch (agentType.value) {
    case 'contract': return '如：帮我查合同“2026年度T恤采购”是否有风险'
    case 'replenishment': return '如：SKU-T001-WH-M 库存够吗？不够就帮我下单'
    case 'bi': return '如：各类商品库存分布如何？'
    default: return '如：库存够吗？/ 搜一下帆布包 / 查合同风险'
  }
})

watch(() => props.initialQuery, (v) => { if (v) input.value = v })

async function loadMode() {
  try {
    const r = await request.get('/agent/mode'); const d = r.data.data || r.data; deepEnabled.value = !!d.pythonSidecarEnabled
  } catch {}
}
async function loadTools() {
  try {
    const res = await request.get('/agent/tools')
    const data = res.data.data || res.data
    toolList.value = Array.isArray(data) ? data : []
  } catch {}
}

async function send() {
  const text = input.value.trim()
  if (!text) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  streamText.value = ''
  await nextTick()
  if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  try {
    if (useStream.value) {
      await sendStream(text)
    } else {
      const res = await api.agentChat({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) } as unknown as Record<string, string>) as unknown as Record<string, unknown>
      messages.value.push({ role: 'assistant', content: String(res.reply || ''), agentType: agentType.value, tools: Array.isArray(res.tools) ? (res.tools as string[]) : undefined, citations: Array.isArray((res as any).citations) ? (res as any).citations : undefined, runId: (res as any).runId })
    }
  } catch {
    messages.value.push({ role: 'assistant', content: '调用失败，请检查后端是否启动。' })
  } finally {
    loading.value = false
    streamText.value = ''
    await nextTick()
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  }
}

async function sendStream(text: string) {
  const resp = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
    body: JSON.stringify({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) }),
  })
  if (!resp.ok || !resp.body) {
    const fallback: { reply: string } = await api.agentChat({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) } as unknown as Record<string, string>)
    messages.value.push({ role: 'assistant', content: fallback.reply, agentType: agentType.value })
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let full = ''
  const idx = messages.value.length
  messages.value.push({ role: 'assistant', content: '', agentType: agentType.value })
  let sseBuf = ''
  let sseEvent = 'message'
  const handleLine = (line: string) => {
    if (line.startsWith('event:')) { sseEvent = line.slice(6).trim(); return }
    if (!line.startsWith('data:')) return
    const payload = line.slice(5).replace(/^ /, '')
    if (sseEvent === 'done') {
      try {
        const info = JSON.parse(payload)
        if (Array.isArray(info.tools) && info.tools.length) messages.value[idx].tools = info.tools
      } catch { /* done 事件解析失败不影响正文 */ }
      return
    }
    if (payload === '[DONE]') return
    full += payload
    messages.value[idx].content = full
    streamText.value = full
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    sseBuf += decoder.decode(value, { stream: true })
    const lines = sseBuf.split('\n')
    sseBuf = lines.pop() ?? ''
    for (const ln of lines) {
      const line = ln.replace(/\r$/, '')
      if (line === '') { sseEvent = 'message'; continue }  // 空行 = 事件边界
      handleLine(line)
    }
    await nextTick()
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  }
}

async function feedback(idx: number, rating: number) {
  try { const m = messages.value[idx] as any; await request.post('/agent/feedback', { runId: m.runId, sessionId: sessionId.value, rating }) ; ElMessage.success(rating>0?'已点赞':'已点踩') } catch {}
}
function clearSession() {
  sessionId.value = 'sess-' + Math.random().toString(36).slice(2, 8)
  messages.value = [{ role: 'assistant', content: '记忆已清空，开启新会话。', agentType: 'Agent' }]
}

onMounted(() => { loadMode(); loadTools(); if (input.value) send() })

defineExpose({ send, messages, agentType, input })
</script>

<style scoped>
.agent-chat { display: flex; flex-direction: column; height: 100%; }
.messages { flex: 1; overflow: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.msg { display: flex; flex-direction: column; gap: 4px; }
.msg.user { align-items: flex-end; }
.role { font-size: 12px; color: #909399; }
.bubble { max-width: 82%; padding: 10px 14px; border-radius: 12px; white-space: pre-wrap; word-break: break-word; line-height: 1.6; }
.msg.user .bubble { background: #409eff; color: #fff; }
.msg.assistant .bubble { background: #f2f3f5; color: #303133; }
.msg.assistant .bubble.md :deep(p) { margin: 0 0 6px; }
.msg.assistant .bubble.md :deep(pre) { background: #282c34; color: #abb2bf; padding: 8px 10px; border-radius: 8px; overflow-x: auto; font-size: 12px; }
.msg.assistant .bubble.md :deep(code) { font-family: Consolas, Menlo, monospace; }
.msg.assistant .bubble.md :deep(ul), .msg.assistant .bubble.md :deep(ol) { margin: 4px 0; padding-left: 20px; }
.msg.assistant .bubble.md :deep(table) { border-collapse: collapse; margin: 6px 0; }
.msg.assistant .bubble.md :deep(th), .msg.assistant .bubble.md :deep(td) { border: 1px solid #dcdfe6; padding: 4px 8px; }
.tool-chips { max-width: 82%; display: flex; flex-wrap: wrap; gap: 2px; }
.input-row { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #ebeef5; align-items: center; }
.tool-bar { padding: 6px 12px; border-top: 1px solid #f2f3f5; display:flex; align-items:center; flex-wrap:wrap; gap:4px }
</style>
