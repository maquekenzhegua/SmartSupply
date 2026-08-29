<template>
  <div class="agent-chat">
    <div class="messages" ref="msgRef">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="role">{{ m.role === 'user' ? '你' : (m.agentType || 'Agent') }}</div>
        <div class="bubble">{{ m.content }}</div>
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
import { api } from '@/api'
import request from '@/utils/request'

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
const messages = ref<{ role: 'user' | 'assistant'; content: string; agentType?: string }[]>([
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
      const res: { reply: string } = await api.agentChat({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) } as unknown as Record<string, string>)
      messages.value.push({ role: 'assistant', content: res.reply, agentType: agentType.value })
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
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const chunk = decoder.decode(value, { stream: true })
    // SseEmitter 默认以事件流格式返回，逐字拼接
    full += chunk.replace(/^data:/gm, '').trim()
    messages.value[idx].content = full
    streamText.value = full
    await nextTick()
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  }
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
.input-row { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #ebeef5; align-items: center; }
.tool-bar { padding: 6px 12px; border-top: 1px solid #f2f3f5; display:flex; align-items:center; flex-wrap:wrap; gap:4px }
</style>
