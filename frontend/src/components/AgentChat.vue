<template>
  <div class="agent-chat">
    <div ref="msgRef" class="messages">
      <div class="thread">
        <!-- 会话开场：仅剩首条问候时展示英雄区 + 快捷提问 -->
        <div v-if="messages.length === 1 && !loading" class="hero">
          <div class="hero-logo"><el-icon :size="22"><Opportunity /></el-icon></div>
          <h2 class="hero-title">SmartSupply Agent</h2>
          <p class="hero-desc">{{ messages[0].content }}</p>
          <div class="hero-chips">
            <button v-for="q in suggestions" :key="q" class="chip" type="button" @click="quickAsk(q)">
              <el-icon :size="12"><ChatDotRound /></el-icon>{{ q }}
            </button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
          <!-- 用户消息：右对齐品牌气泡 -->
          <template v-if="m.role === 'user'">
            <div class="bubble user-bubble">{{ m.content }}</div>
          </template>

          <!-- 助手消息：头像 + 无气泡正文 -->
          <template v-else>
            <div class="assistant-row">
              <div class="bot-avatar"><el-icon :size="13"><Opportunity /></el-icon></div>
              <div class="assistant-body">
                <div class="role">{{ m.agentType || 'Agent' }}</div>

                <div v-if="m.tools && m.tools.length" class="tool-block">
                  <button class="fold-head" type="button" @click="toggle(i, 'tools')">
                    <el-icon :size="12"><Operation /></el-icon>
                    <span>已调用 {{ m.tools.length }} 个工具</span>
                    <el-icon :size="12" class="fold-caret" :class="{ open: isOpen(i, 'tools') }"><ArrowDown /></el-icon>
                  </button>
                  <div v-if="isOpen(i, 'tools')" class="fold-body">
                    <el-tag v-for="(tool, ti) in m.tools" :key="ti" size="small" effect="plain" round>{{ tool.split('(')[0] }}</el-tag>
                  </div>
                </div>

                <div class="bubble md" v-html="renderMd(m.content)"></div>

                <!-- 写闸门：HITL 人工批准 / 快路径二次确认 -->
                <div v-if="m.needConfirm" class="confirm-row">
                  <el-icon :size="14" class="confirm-icon"><WarningFilled /></el-icon>
                  <template v-if="m.threadId">
                    <el-button type="danger" size="small" @click="confirmWrite(i, true)">批准执行</el-button>
                    <el-button size="small" @click="confirmWrite(i, false)">拒绝</el-button>
                    <span class="confirm-note">Agent 写操作需人工批准（HITL），批准后继续执行</span>
                  </template>
                  <template v-else>
                    <el-button type="warning" size="small" @click="confirmWrite(i, true)">确认创建采购单</el-button>
                    <span class="confirm-note">写操作需二次确认，确认后将重新提交</span>
                  </template>
                </div>

                <div v-if="m.runId" class="trace-row">
                  <el-button link size="small" type="primary" @click="toggleTrace(i)">
                    {{ traces[i] ? '收起推理轨迹' : '查看推理轨迹' }}
                  </el-button>
                  <div v-if="traces[i]" class="trace-box">
                    <div class="trace-meta">{{ traces[i].run.mode }} · {{ traces[i].run.status }} · {{ traces[i].run.latency_ms }}ms</div>
                    <div v-for="(s, si) in traces[i].steps" :key="si" class="trace-line">
                      {{ s.seq }}. {{ s.node }}<template v-if="s.name && s.name !== s.node"> · {{ s.name }}</template><template v-if="s.success === false"> ⚠失败</template>
                    </div>
                    <div v-for="(t, ti) in traces[i].toolCalls" :key="'t'+ti" class="trace-line">
                      <el-icon :size="11"><Operation /></el-icon> {{ t.tool }} <span class="trace-args">{{ t.args_json }}</span><span v-if="t.success === false" class="trace-fail">（失败）</span>
                    </div>
                  </div>
                </div>

                <!-- RAG 引用来源 -->
                <div v-if="m.citations && m.citations.length" class="tool-block">
                  <button class="fold-head" type="button" @click="toggle(i, 'citations')">
                    <el-icon :size="12"><Document /></el-icon>
                    <span>引用 {{ m.citations.length }} 条来源</span>
                    <el-icon :size="12" class="fold-caret" :class="{ open: isOpen(i, 'citations') }"><ArrowDown /></el-icon>
                  </button>
                  <div v-if="isOpen(i, 'citations')" class="fold-body citation-list">
                    <div v-for="c in m.citations" :key="c.idx" class="citation">
                      <span class="cite-idx">[{{ c.idx }}]</span>
                      <span class="cite-title">{{ c.title }}</span>
                      <span class="cite-score">score={{ Number(c.score).toFixed(3) }}</span>
                      <div class="cite-snippet">{{ c.snippet }}</div>
                    </div>
                  </div>
                </div>

                <div v-if="m.runId" class="feedback-row">
                  <el-button link size="small" class="fb" @click="feedback(i, 1)">👍</el-button>
                  <el-button link size="small" class="fb" @click="feedback(i, -1)">👎</el-button>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- 生成中占位 -->
        <div v-if="loading" class="msg assistant">
          <div class="assistant-row">
            <div class="bot-avatar pulse"><el-icon :size="13"><Opportunity /></el-icon></div>
            <div class="assistant-body">
              <div class="role">Agent</div>
              <div class="bubble typing">思考中…<span v-if="statusText" class="typing-status">{{ statusText }}</span><span v-else-if="streamText" class="typing-status">{{ streamText.slice(0, 40) }}…</span></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="toolList.length" class="tool-bar">
      <span class="tool-bar-label">可用工具</span>
      <el-tag v-for="t in toolList" :key="t.name" size="small" effect="plain" round class="tool-tag">{{ t.name }}</el-tag>
    </div>

    <!-- 输入区：保留 .input-row 类名（e2e 依赖），重构为胶囊焦点区 -->
    <div class="input-row">
      <el-input
        v-model="input"
        class="composer-input"
        :placeholder="placeholderText"
        size="large"
        @keyup.enter="send"
      />
      <div class="composer-meta">
        <el-select v-model="agentType" class="agent-select" size="default">
          <el-option label="通用助手" value="general" />
          <el-option label="合同风控" value="contract" />
          <el-option label="补货预测" value="replenishment" />
          <el-option label="经营分析" value="bi" />
        </el-select>
        <el-switch v-model="useStream" active-text="流式" size="small" />
        <el-switch v-model="useDeep" active-text="深度推理" size="small" :disabled="!deepEnabled" :title="deepEnabled ? '走 Python LangGraph 边车' : '需启动 agent-python 且 Java 开启 AGENT_PYTHON_ENABLED'" />
        <div class="composer-spacer"></div>
        <el-button link size="small" class="clear-btn" @click="clearSession"><el-icon><RefreshLeft /></el-icon>清空记忆</el-button>
        <el-button v-if="loading" size="default" @click="stopGen">停止生成</el-button>
        <el-button v-else type="primary" size="default" :icon="Promotion" @click="send">发送</el-button>
      </div>
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
import { Promotion } from '@element-plus/icons-vue'

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
  { role: 'assistant', content: '你好，我是 SmartSupply Agent，可查库存、创建采购单、搜合同/商品，支持多轮记忆。试试："哪些SKU低于安全库存？"或"帮我查一下T恤的SKU"。', agentType: 'Agent' },
])
const traces = ref<Record<number, { run: any; steps: any[]; toolCalls: any[] }>>({})
const msgRef = ref<HTMLElement>()

// 生成中止控制：用户可点"停止生成"，组件卸载时也会中止，避免流在后台继续消耗
let abortController: AbortController | null = null
function stopGen() {
  abortController?.abort()
}
onBeforeUnmount(() => abortController?.abort())

// 工具调用/引用来源折叠卡片状态
const folds = ref<Record<string, boolean>>({})
function isOpen(i: number, kind: 'tools' | 'citations') { return !!folds.value[`${kind}-${i}`] }
function toggle(i: number, kind: 'tools' | 'citations') { folds.value[`${kind}-${i}`] = !folds.value[`${kind}-${i}`] }

// 英雄区快捷提问：直接发送
const suggestions = [
  '哪些 SKU 低于安全库存？',
  '帮我查一下 T 恤的 SKU',
  '各类商品库存分布如何？',
  '查一下合同里有没有无限连带责任条款',
]
function quickAsk(text: string) {
  input.value = text
  send()
}

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
    case 'contract': return '如：帮我查合同"2026年度T恤采购"是否有风险'
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
.agent-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--bg-page);
}

.thread {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px 20px 12px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* ------- 英雄区 ------- */
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48px 16px 8px;
}

.hero-logo {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border-radius: 16px;
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: 0 8px 20px rgba(79, 70, 229, 0.35);
}

.hero-title {
  margin: 16px 0 6px;
  font-size: 20px;
}

.hero-desc {
  margin: 0;
  max-width: 460px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ink-500);
}

.hero-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 22px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  font-size: 13px;
  color: var(--ink-700);
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.chip:hover {
  color: var(--brand-700);
  border-color: var(--brand-300);
  background: var(--brand-50);
}

/* ------- 消息 ------- */
.msg {
  display: flex;
  flex-direction: column;
}

.msg.user {
  align-items: flex-end;
}

.bubble {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.65;
}

.user-bubble {
  max-width: 78%;
  padding: 10px 16px;
  border-radius: 16px 16px 4px 16px;
  background: var(--brand-600);
  color: #fff;
  font-size: 14px;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.25);
}

.assistant-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.bot-avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  margin-top: 2px;
  border-radius: 9px;
  background: var(--brand-gradient);
  color: #fff;
}

.bot-avatar.pulse {
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

.assistant-body {
  min-width: 0;
  flex: 1;
}

.role {
  font-size: 12px;
  color: var(--ink-400);
  margin-bottom: 4px;
}

.msg.assistant .bubble.md {
  color: var(--ink-900);
}

/* 折叠卡片：工具调用 / 引用 */
.tool-block {
  margin: 2px 0 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}

.fold-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: transparent;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-500);
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.fold-head:hover {
  background: var(--ink-50);
}

.fold-caret {
  margin-left: auto;
  transition: transform 0.18s ease;
}

.fold-caret.open {
  transform: rotate(180deg);
}

.fold-body {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 12px 10px;
}

/* 引用列表 */
.citation-list {
  flex-direction: column;
  gap: 8px;
}

.citation {
  position: relative;
  padding: 8px 10px 8px 34px;
  border-radius: 8px;
  background: var(--ink-50);
  font-size: 12.5px;
}

.cite-idx {
  position: absolute;
  left: 10px;
  top: 8px;
  font-weight: 700;
  color: var(--brand-600);
  font-variant-numeric: tabular-nums;
}

.cite-title {
  font-weight: 600;
  color: var(--ink-900);
}

.cite-score {
  margin-left: 8px;
  color: var(--ink-400);
  font-variant-numeric: tabular-nums;
}

.cite-snippet {
  margin-top: 3px;
  color: var(--ink-500);
  line-height: 1.55;
}

/* 写闸门确认条 */
.confirm-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 14px;
  border: 1px solid #fde68a;
  background: #fffbeb;
  border-radius: 10px;
}

.confirm-icon {
  color: var(--warning);
}

.confirm-note {
  font-size: 12px;
  color: var(--ink-500);
}

/* 推理轨迹 */
.trace-row {
  margin-top: 8px;
}

.trace-box {
  margin-top: 6px;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.trace-meta {
  font-size: 12px;
  color: var(--ink-600);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.trace-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-500);
  font-family: var(--font-mono);
}

.trace-args {
  color: var(--ink-400);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-fail {
  color: var(--warning);
}

.feedback-row {
  display: flex;
  gap: 2px;
  margin-top: 6px;
}

.fb {
  font-size: 12px;
}

/* 生成中 */
.typing {
  font-size: 14px;
  color: var(--ink-500);
}

.typing-status {
  margin-left: 6px;
  color: var(--ink-400);
  font-size: 12px;
}

/* ------- 工具栏 ------- */
.tool-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  padding: 6px 20px 4px;
}

.tool-bar-label {
  font-size: 12px;
  color: var(--ink-400);
}

.tool-tag {
  color: var(--ink-500);
}

/* ------- 输入区（.input-row 类名供 e2e 使用） ------- */
.input-row {
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
  padding: 0 20px 18px;
}

.input-row :deep(.composer-input .el-input__wrapper) {
  border-radius: 14px;
  padding: 6px 16px;
  box-shadow: 0 0 0 1px var(--border) inset, var(--shadow-card);
  transition: box-shadow 0.18s ease;
}

.input-row :deep(.composer-input .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--brand-500) inset, 0 0 0 4px var(--brand-100);
}

.composer-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 8px;
  padding: 0 4px;
}

.agent-select {
  width: 128px;
}

.composer-spacer {
  flex: 1;
}

.clear-btn {
  color: var(--ink-400);
}

.clear-btn:hover {
  color: var(--ink-600);
}
</style>
