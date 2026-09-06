<template>
  <div class="agent-chat">
    <div ref="msgRef" class="messages">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="role">{{ m.role === 'user' ? '你' : (m.agentType || 'Agent') }}</div>
        <div v-if="m.role === 'assistant' && m.tools && m.tools.length" class="tool-chips">
          <el-tag v-for="(tool, ti) in m.tools" :key="ti" type="warning" effect="plain" size="small" style="margin-right:4px">
            🔧 {{ tool.split('(')[0] }}
          </el-tag>
        </div>
        <div v-if="m.role === 'assistant'" class="bubble md" v-html="renderMd(m.content)"></div>
        <div v-else class="bubble">{{ m.content }}</div>
        <div v-if="m.needConfirm" class="confirm-row">
          <template v-if="m.threadId">
            <el-button type="danger" size="small" @click="confirmWrite(i, true)">批准执行</el-button>
            <el-button size="small" @click="confirmWrite(i, false)">拒绝</el-button>
            <span style="font-size:12px;color:#909399">Agent 写操作需人工批准（HITL），批准后继续执行</span>
          </template>
          <template v-else>
            <el-button type="warning" size="small" @click="confirmWrite(i, true)">确认创建采购单</el-button>
            <span style="font-size:12px;color:#909399">写操作需二次确认，确认后将重新提交</span>
          </template>
        </div>
        <div v-if="m.role === 'assistant' && (m as any).runId" style="margin-top:2px">
          <el-button link size="small" type="primary" @click="toggleTrace(i)">{{ traces[i] ? '收起推理轨迹' : '查看推理轨迹' }}</el-button>
          <div v-if="traces[i]" class="trace-box">
            <div style="font-size:12px;color:#606266">{{ traces[i].run.mode }} · {{ traces[i].run.status }} · {{ traces[i].run.latency_ms }}ms</div>
            <div v-for="(s, si) in traces[i].steps" :key="si" style="font-size:12px;color:#909399">
              {{ s.seq }}. {{ s.node }}<template v-if="s.name && s.name !== s.node"> · {{ s.name }}</template><template v-if="s.success === false"> ⚠失败</template>
            </div>
            <div v-for="(t, ti) in traces[i].toolCalls" :key="'t'+ti" style="font-size:12px">
              🔧 {{ t.tool }} <span style="color:#909399">{{ t.args_json }}</span><span v-if="t.success === false" style="color:#e6a23c">（失败）</span>
            </div>
          </div>
        </div>
        <div v-if="m.role === 'assistant' && m.citations && m.citations.length" class="citations">
          <div style="font-size:12px; color:#909399; margin-top:4px">引用</div>
          <div v-for="c in m.citations" :key="c.idx" style="font-size:12px; background:#fafafa; padding:4px 8px; border-radius:6px; margin:4px 0">
            <b>[{{ c.idx }}] {{ c.title }}</b> <span style="color:#909399">score={{ Number(c.score).toFixed(3) }}</span><br />{{ c.snippet }}
          </div>
        </div>
        <div v-if="m.role === 'assistant'" style="display:flex; gap:6px; margin-top:4px">
          <el-button size="small" @click="feedback(i, 1)">👍</el-button>
          <el-button size="small" @click="feedback(i, -1)">👎</el-button>
        </div>
      </div>
      <div v-if="loading" class="msg assistant"><div class="bubble">思考中…<span v-if="statusText" style="color:#909399">{{ statusText }}</span><span v-else-if="streamText">{{ streamText.slice(0, 40) }}…</span></div></div>
    </div>
    <div v-if="toolList.length" class="tool-bar">
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
      <el-button v-if="loading" size="large" @click="stopGen">停止生成</el-button>
      <el-button v-else type="primary" size="large" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import request from '@/utils/request'
import { SseParser } from '@/utils/sse'

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
const statusText = ref('')
const sessionId = ref('sess-' + Math.random().toString(36).slice(2, 8))
const toolList = ref<{ name: string; desc: string }[]>([])
const messages = ref<{ role: 'user' | 'assistant'; content: string; agentType?: string; tools?: string[]; citations?: any[]; runId?: number; needConfirm?: boolean; raw?: string; threadId?: string; confirmInfo?: any }[]>([
  { role: 'assistant', content: '你好，我是 SmartSupply Agent，可查库存、创建采购单、搜合同/商品，支持多轮记忆。试试：“哪些SKU低于安全库存？”或“帮我查一下T恤的SKU”。', agentType: 'Agent' },
])
const traces = ref<Record<number, { run: any; steps: any[]; toolCalls: any[] }>>({})
const msgRef = ref<HTMLElement>()

// 生成中止控制：用户可点"停止生成"，组件卸载时也会中止，避免流在后台继续消耗
let abortController: AbortController | null = null
function stopGen() {
  abortController?.abort()
}
onBeforeUnmount(() => abortController?.abort())

async function toggleTrace(i: number) {
  const m = messages.value[i] as any
  if (traces.value[i]) { delete traces.value[i]; return }
  if (!m.runId) return
  try {
    const r = await request.get(`/agent/runs/${m.runId}/trace`)
    traces.value[i] = r.data.data || r.data
  } catch {
    traces.value[i] = { run: { mode: '?', status: '轨迹查询失败', latency_ms: 0 }, steps: [], toolCalls: [] }
  }
}

function confirmWrite(i: number, approved = true) {
  const m = messages.value[i] as any
  m.needConfirm = false
  // 深度模式 HITL：图内 interrupt 挂起，携 threadId 以 resume 恢复（批准→执行写工具；拒绝→如实取消）
  if (m.threadId) {
    loading.value = true
    streamText.value = ''
    statusText.value = approved ? '已批准，继续执行…' : '已拒绝，取消写操作…'
    sendStream('', false, { threadId: m.threadId, approved }).finally(() => {
      loading.value = false
      streamText.value = ''
      statusText.value = ''
    })
    return
  }
  if (useStream.value) { sendStream(m.raw || '', true) }
  else {
    api.agentChat({ message: m.raw || '', agentType: agentType.value, sessionId: sessionId.value, confirmCreate: 'true', useDeep: String(useDeep.value) } as unknown as Record<string, string>)
      .then((res: any) => { messages.value.push({ role: 'assistant', content: String(res.reply || ''), agentType: agentType.value, runId: res.runId }) })
      .catch(() => { messages.value.push({ role: 'assistant', content: '确认执行失败，请重试。' }) })
  }
}

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
  statusText.value = ''
  await nextTick()
  if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  try {
    if (useStream.value) {
      await sendStream(text)
    } else {
      const res = await api.agentChat({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) } as unknown as Record<string, string>) as unknown as Record<string, unknown>
      const degradedNote = res.degraded ? '\n\n⚠ 该回复由降级链路产生（' + String((res as any).degradeReason || '原因未上报') + '），关键数字请自行核验。' : ''
      messages.value.push({
        role: 'assistant',
        content: String(res.reply || '') + degradedNote,
        agentType: agentType.value,
        tools: Array.isArray(res.tools) ? (res.tools as string[]) : undefined,
        citations: Array.isArray((res as any).citations) ? (res as any).citations : undefined,
        runId: (res as any).runId,
        needConfirm: !!res.needConfirm,
        raw: res.needConfirm ? text : undefined,
        threadId: (res as any).threadId || undefined,
        confirmInfo: Array.isArray((res as any).confirm) && (res as any).confirm[0] ? (res as any).confirm[0] : undefined,
      })
    }
  } catch {
    messages.value.push({ role: 'assistant', content: '调用失败，请检查后端是否启动。' })
  } finally {
    loading.value = false
    streamText.value = ''
    statusText.value = ''
    await nextTick()
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  }
}

function sseTimeoutMs() {
  // 深度模式多步 ReAct 可到数分钟；普通模式 2 分钟足够暴露挂死
  return useDeep.value ? 600_000 : 120_000
}

async function sendStream(text: string, confirmed = false, resumeInfo?: { threadId: string; approved: boolean }) {
  abortController = new AbortController()
  const timeoutId = window.setTimeout(() => abortController?.abort(), sseTimeoutMs())
  let streamStarted = false
  let bubble: { role: 'user' | 'assistant'; content: string; agentType?: string } | null = null
  try {
    const resp = await fetch('/api/agent/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
      body: JSON.stringify({
        message: text, agentType: agentType.value, sessionId: sessionId.value,
        useDeep: resumeInfo ? 'true' : String(useDeep.value), confirmCreate: String(confirmed),
        ...(resumeInfo ? { resume: 'true', threadId: resumeInfo.threadId, confirmApprove: String(resumeInfo.approved) } : {}),
      }),
      signal: abortController.signal,
    })
    if (!resp.ok || !resp.body) {
      // 不再静默降级：鉴权/限流类错误如实呈现；仅 5xx/网关异常尝试一次非流式兜底
      let msg = `流式请求失败（HTTP ${resp.status}）`
      try {
        const body = await resp.json()
        if (body?.msg) msg = String(body.msg)
      } catch { /* 非 JSON 响应体，保留状态码信息 */ }
      if (resp.status >= 500) {
        await nonStreamFallback(text, msg)
      } else {
        messages.value.push({ role: 'assistant', content: `⚠ ${msg}` })
      }
      return
    }
    streamStarted = true
    const parser = new SseParser()
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let full = ''
    // 以对象引用而非索引持有气泡：中途 push 新消息（如用户回车）也不会写错行
    bubble = { role: 'assistant', content: '', agentType: agentType.value }
    messages.value.push(bubble)
    const target = bubble as any
    const handleEvent = (event: string, payload: string) => {
      if (event === 'done') {
        try {
          const info = JSON.parse(payload)
          if (Array.isArray(info.tools) && info.tools.length) target.tools = info.tools
          if (info.runId) target.runId = info.runId
          // 边车事件用 snake_case（thread_id），Java 响应用 camelCase（threadId），两者都兼容
          if (info.threadId || info.thread_id) target.threadId = info.threadId || info.thread_id
          if (info.degraded) target.content += '\n\n⚠ 深度推理降级回复：' + String(info.degradeReason || '')
          if (info.interrupted) {
            target.needConfirm = true
            if (!target.content) target.content = 'Agent 请求执行写操作，需要人工批准。'
          }
        } catch { /* done 事件解析失败不影响正文 */ }
        return
      }
      if (event === 'trace') {
        // 深度模式事件流：规划/取证步骤实时可见（工具 chips 同步点亮）
        try {
          const info = JSON.parse(payload)
          if (info.tool) {
            const list = target.tools || (target.tools = [])
            const name = String(info.tool).split('(')[0]
            if (!list.includes(name)) list.push(name)
            statusText.value = info.ok === false ? `取证失败：${name}` : `取证中：${name}`
          } else if (Array.isArray(info.calls) && info.calls.length) {
            statusText.value = (info.calls[0] && info.calls[0].tool) ? `规划：${info.calls.map((c: any) => c.tool).join(' + ')}` : ''
          }
        } catch { /* trace 事件解析失败不影响正文 */ }
        return
      }
      if (event === 'confirm') {
        // 写闸门挂起（深度模式 HITL）：threadId 保留在气泡上，批准/拒绝以 resume 恢复
        try {
          const info = JSON.parse(payload)
          target.content = info.question || info.reply || '该写操作需要二次确认。'
          target.needConfirm = true
          target.raw = text
          if (info.threadId || info.thread_id) target.threadId = info.threadId || info.thread_id
          if (info.tool) target.confirmInfo = info
        } catch {
          target.content = '该写操作需要二次确认。'; target.needConfirm = true; target.raw = text
        }
        return
      }
      if (event === 'notice') { full += payload + '\n'; target.content = full; return }
      if (payload === '[DONE]') return
      full += payload
      target.content = full
      streamText.value = full
    }
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      for (const ev of parser.push(decoder.decode(value, { stream: true }))) {
        handleEvent(ev.event, ev.data)
      }
      await nextTick()
      if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
    }
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      // 用户主动停止：保留已生成部分并如实标注
      if (streamStarted && bubble) {
        bubble.content += '\n\n（已手动停止生成）'
      }
      return
    }
    // 网络中断：保留已有内容，追加错误说明，便于用户重试而非丢失全部
    if (streamStarted && bubble) {
      bubble.content += '\n\n⚠ 连接中断，以上为已生成部分，请重发消息重试。'
    } else {
      messages.value.push({ role: 'assistant', content: '连接失败，请检查后端是否启动。' })
    }
  } finally {
    window.clearTimeout(timeoutId)
    abortController = null
  }
}

/** 流式通道异常时的非流式兜底：显式标注降级，不再静默伪装成流式结果 */
async function nonStreamFallback(text: string, reason: string) {
  try {
    const fallback: any = await api.agentChat({ message: text, agentType: agentType.value, sessionId: sessionId.value, useDeep: String(useDeep.value) } as unknown as Record<string, string>)
    messages.value.push({
      role: 'assistant',
      content: String(fallback.reply || '') + `\n\n⚠ 流式通道异常（${reason}），本条为非流式兜底结果。`,
      agentType: agentType.value,
    })
  } catch {
    messages.value.push({ role: 'assistant', content: `⚠ 流式请求失败（${reason}），非流式兜底也失败了，请检查后端。` })
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
.confirm-row { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
.trace-box { border: 1px solid #ebeef5; border-radius: 8px; padding: 6px 10px; background: #fafafa; display: flex; flex-direction: column; gap: 2px; max-width: 82%; }
.input-row { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #ebeef5; align-items: center; }
.tool-bar { padding: 6px 12px; border-top: 1px solid #f2f3f5; display:flex; align-items:center; flex-wrap:wrap; gap:4px }
</style>
