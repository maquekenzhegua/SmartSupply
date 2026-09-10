package com.smartsupply.agent;

import com.smartsupply.agent.memory.ChatMemoryService;
import com.smartsupply.agent.rag.RagService;
import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import com.smartsupply.common.TraceIdFilter;
import com.smartsupply.common.CurrentUser;
import com.smartsupply.common.TokenContext;
import com.smartsupply.config.MuseSparkChatModel;
import com.smartsupply.config.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final ChatClient chatClient;
    private final RagService ragService;
    private final ChatMemoryService memory;
    private final com.smartsupply.agent.tools.InventoryTools inventoryTools;
    private final com.smartsupply.agent.tools.PurchaseTools purchaseTools;
    private final com.smartsupply.agent.tools.ContractTools contractTools;
    private final com.smartsupply.agent.tools.CatalogTools catalogTools;
    private final PythonSidecarService pythonSidecar;
    private final PromptRegistry prompts;
    private final ObservationService observation;
    private final PromptGuard guard;
    private final TokenEstimator tokenEstimator;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.smartsupply.agent.cost.AgentBudgetService budget;
    private final AgentTypeResolver resolver;
    private final boolean chatMock;
    private final String aiModelName;
    private final boolean embeddingMock;
    // 有界 SSE 线程池：无界 cachedThreadPool 每条流占一线程，恶意/异常并发下无线程上限。
    // 上限 32 + 有界队列，打满时由调用线程兜底泵事件（退化为同步，仍可服务而非拒绝）
    private final ExecutorService sseExecutor = new java.util.concurrent.ThreadPoolExecutor(
            8, 32, 60, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "agent-sse");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    public AgentController(ChatClient chatClient, RagService ragService, ChatMemoryService memory,
                           com.smartsupply.agent.tools.InventoryTools inventoryTools,
                           com.smartsupply.agent.tools.PurchaseTools purchaseTools,
                           com.smartsupply.agent.tools.ContractTools contractTools,
                           com.smartsupply.agent.tools.CatalogTools catalogTools,
                           PythonSidecarService pythonSidecar, PromptRegistry prompts, ObservationService observation,
                           PromptGuard guard, TokenEstimator tokenEstimator, org.springframework.jdbc.core.JdbcTemplate jdbc,
                           com.smartsupply.agent.cost.AgentBudgetService budget, AgentTypeResolver resolver,
                           @org.springframework.beans.factory.annotation.Value("${smartsupply.ai.mock:true}") boolean chatMock,
                           @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String aiModelName,
                           @org.springframework.beans.factory.annotation.Value("${smartsupply.ai.embedding-mock:false}") boolean embeddingMock) {
        this.chatClient = chatClient;
        this.ragService = ragService;
        this.memory = memory;
        this.inventoryTools = inventoryTools;
        this.purchaseTools = purchaseTools;
        this.contractTools = contractTools;
        this.catalogTools = catalogTools;
        this.pythonSidecar = pythonSidecar;
        this.prompts = prompts;
        this.observation = observation;
        this.guard = guard;
        this.tokenEstimator = tokenEstimator;
        this.jdbc = jdbc;
        this.budget = budget;
        this.resolver = resolver;
        this.chatMock = chatMock;
        this.aiModelName = aiModelName;
        this.embeddingMock = embeddingMock;
    }

    @PostMapping("/chat")
    @RateLimit(permitsPerMinute = 30, key = "agent-chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long start = System.currentTimeMillis();
        String rawMessage = body.getOrDefault("message", "");
        String message = guard.sanitizeUserInput(rawMessage);
        boolean flagged = guard.containsInjection(rawMessage);
        String clientType = body.getOrDefault("agentType", "general");
        final String user = com.smartsupply.common.CurrentUser.username();
        String rawSessionId = body.getOrDefault("sessionId", "default-" + user);
        // "default" 归一化为按用户隔离的缺省会话；final 以便后续 lambda 捕获
        final String sessionId = "default".equals(rawSessionId) ? "default-" + user : rawSessionId;
        if (!memory.canAccess(sessionId, user)) {
            return Result.fail(403, "无权访问该会话");
        }
        // 成本闸门：日预算超限直接拒绝（先于任何 LLM/工具调用），不降级不伪装——花不起就是花不起
        var budgetStatus = budget.check(user);
        if (budgetStatus.over()) {
            return Result.fail(429, "今日 Agent 成本预算已用尽（" + budgetStatus.describe() + "）。请联系管理员调整限额或明日再试。");
        }
        // agent_type 服务端裁决：deep 是运行模式不是人设，人设按消息内容归类，归类结果进台账
        AgentTypeResolver.Resolved resolvedType = resolver.resolve("deep".equals(clientType) ? "auto" : clientType, message);
        String agentType = resolvedType.agentType();
        boolean requireConfirm = "true".equalsIgnoreCase(body.getOrDefault("confirmCreate", "false"));
        final String resumeThreadId = String.valueOf(body.getOrDefault("threadId", "")).trim();
        final boolean isResume = "true".equalsIgnoreCase(body.getOrDefault("resume", "false")) && !resumeThreadId.isBlank();
        final boolean confirmApproved = "true".equalsIgnoreCase(body.getOrDefault("confirmApprove", "false"));
        boolean useDeep0 = "deep".equals(clientType) || "true".equalsIgnoreCase(body.getOrDefault("useDeep", "false"));
        boolean deepGateActive = useDeep0 && pythonSidecar.isEnabled();
        if (message.isBlank() && !isResume) return Result.fail(400, "message 不能为空");
        // 写意图闸门（java-direct 兜底）：深度模式跳过，图内 interrupt() 是更可靠的 HITL（见 stream 同注）
        if (isWriteIntent(message) && !requireConfirm && !isResume && !deepGateActive) {
            return Result.ok(Map.of("reply", "该操作将创建采购单（DRAFT，需人工审批）。请回复\"确认创建\"并再次提交，或在界面点击二次确认。", "agentType", agentType, "sessionId", sessionId, "needConfirm", true, "flagged", flagged));
        }
        String systemPrompt = prompts.contentFor(agentType);
        String promptVersion = prompts.versionFor(agentType);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        if (traceId == null || traceId.isBlank()) traceId = com.smartsupply.common.TraceContext.get();
        if (traceId == null) traceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String ragContext = "";
        RagService.RecallDetail detail = null;
        if (shouldRecall(agentType, message)) {
            detail = ragService.recallWithDetail(message);
            ragContext = detail.context();
        }
        List<Message> history = memory.load(sessionId, systemPrompt);
        String userContent = guard.wrapUserContent(message, ragContext);
        // persistent run
        Long uid = null; try { uid = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, user); } catch (Exception ignored) {}
        // 台账口径修正：model 列记录真实对话模型（此前错存 prompt 版本），prompt_version 独立归因
        String runModel = chatMock ? "mock" : aiModelName;
        long runId = observation.insertRun(traceId, user, uid, sessionId, agentType, "java-direct", runModel, promptVersion);
        boolean useDeep = useDeep0;
        if (useDeep && pythonSidecar.isEnabled()) {
            List<Map<String, String>> msgs = new ArrayList<>();
            if (isResume) {
                // 恢复请求：图状态从边车 checkpoint 恢复，messages 被忽略
            } else {
                msgs.add(Map.of("role", "system", "content", systemPrompt));
                for (Message m : history) {
                    String txt = m.getText();
                    if (txt == null || txt.equals(systemPrompt)) continue;
                    String role = (m instanceof org.springframework.ai.chat.messages.UserMessage) ? "user" : (m instanceof org.springframework.ai.chat.messages.AssistantMessage) ? "assistant" : "user";
                    msgs.add(Map.of("role", role, "content", txt));
                }
                msgs.add(Map.of("role", "user", "content", userContent));
            }
            long sidecarStart = System.currentTimeMillis();
            PythonSidecarService.SidecarResult sr = pythonSidecar.reasonStructured(msgs, agentType, sessionId, authorization,
                    isResume ? resumeThreadId : "", isResume ? Map.of("approved", confirmApproved) : null);
            if (sr != null && sr.interrupted()) {
                // 写闸门挂起：needConfirm 响应携带 threadId/confirm，前端确认后携 resume 恢复
                if (!isResume) memory.append(sessionId, "user", message, user);
                Map<String, Object> confirmData = new java.util.HashMap<>();
                confirmData.put("reply", "Agent 请求执行写操作，需要人工批准。");
                confirmData.put("agentType", agentType);
                confirmData.put("sessionId", sessionId);
                confirmData.put("needConfirm", true);
                confirmData.put("interrupted", true);
                confirmData.put("threadId", sr.threadId());
                confirmData.put("confirm", sr.confirm());
                confirmData.put("flagged", flagged);
                return Result.ok(confirmData);
            }
            if (sr != null && sr.reply() != null && !sr.reply().isBlank()) {
                String deepReply = sr.reply();
                if (!isResume) memory.append(sessionId, "user", message, user);
                memory.append(sessionId, "assistant", deepReply, user);
                DeepUsage du = deepUsage(sr, tokenEstimator.estimate(systemPrompt + userContent) + estimateHistoryTokens(history), tokenEstimator.estimate(deepReply));
                int promptTokens = du.promptTokens();
                int completionTokens = du.completionTokens();
                String tokenSource = du.actual() ? "actual" : "estimated";
                double cost = du.costUsd();
                long lat = System.currentTimeMillis() - start;
                // degraded 深度响应不得记为 SUCCESS：LLM/工具故障下的降级产物计入成功会污染台账与评测
                String deepStatus = sr.degraded() ? "DEGRADED" : "SUCCESS";
                observation.recordChat(agentType, sr.degraded() ? "python-deep-degraded" : "python-deep", lat, promptTokens, completionTokens, cost, traceId, tokenSource);
                if (runId > 0) {
                    // insertRun 在分支前落库时 mode 固定为 java-direct，深度模式成功需纠正台账口径
                    observation.updateRunMode(runId, sr.degraded() ? "python-deep-degraded" : "python-deep");
                    observation.completeRun(runId, deepStatus, lat, null, promptTokens, completionTokens, cost, tokenSource,
                            sr.degraded() ? sr.degradeReason() : null);
                    budget.addCost(user, cost);
                    observation.insertStep(runId, 1, "llm", "python-sidecar", sha(systemPrompt), sha(deepReply), System.currentTimeMillis() - sidecarStart, !sr.degraded());
                    insertPythonTraceSteps(runId, sr.trace(), 2);
                    insertPythonToolCalls(runId, sr.toolResults(), new com.fasterxml.jackson.databind.ObjectMapper(), firstRole());
                }
                if (detail != null) observation.recordRag(detail.latencyMs(), detail.reranked());
                Map<String, Object> deepData = new java.util.HashMap<>();
                deepData.put("reply", deepReply);
                deepData.put("agentType", agentType);
                deepData.put("sessionId", sessionId);
                deepData.put("mode", "python-deep");
                deepData.put("via", "langgraph");
                deepData.put("promptVersion", promptVersion);
                deepData.put("traceId", traceId);
                deepData.put("flagged", flagged);
                deepData.put("tokenSource", tokenSource);
                deepData.put("agentTypeSource", resolvedType.source());
                deepData.put("runId", runId);
                deepData.put("degraded", sr.degraded());
                if (sr.degraded()) deepData.put("degradeReason", sr.degradeReason());
                return Result.ok(deepData);
            }
            // 边车不可用（null）或空响应：落入下方 java-direct，台账 mode 保持 java-direct，口径真实
        }
        var spec = chatClient.prompt().system(systemPrompt);
        for (Message m : history) {
            if (m.getText() == null || m.getText().equals(systemPrompt)) continue;
            spec = spec.messages(m);
        }
        com.smartsupply.agent.tools.ToolSecurity.beginToolTrace();
        String reply;
        List<String> usedTools;
        long llmStart = System.currentTimeMillis();
        Map<String, Object> citationFlags = new java.util.HashMap<>();
        try {
            reply = spec.user(userContent)
                    .tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                    .call().content();
        } finally {
            usedTools = com.smartsupply.agent.tools.ToolSecurity.endToolTrace();
        }
        reply = enforceCitation(reply, ragContext, citationTitles(detail), citationFlags);
        memory.append(sessionId, "user", message, user);
        // persist tool calls json（写入本体在 ChatMemoryService：jsonb CAST 方言处理在那一层）
        try {
            if (!usedTools.isEmpty()) {
                Long sid = memory.findSessionDbId(sessionId);
                if (sid != null) {
                    String toolJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(usedTools);
                    memory.persistAssistantToolCalls(sid, toolJson);
                }
            }
        } catch (Exception e) {
            log.warn("persist tool_calls_json failed (non-fatal): {}", e.toString());
        }
        TokenContext.Usage usage = MuseSparkChatModel.consumeUsage();
        int promptTokens;
        int completionTokens;
        String tokenSource;
        if (usage != null) {
            promptTokens = usage.promptTokens() > 0 ? usage.promptTokens() : tokenEstimator.estimate(systemPrompt + userContent) + estimateHistoryTokens(history);
            completionTokens = usage.completionTokens() > 0 ? usage.completionTokens() : tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = usage.source();
        } else {
            promptTokens = tokenEstimator.estimate(systemPrompt + userContent) + estimateHistoryTokens(history);
            completionTokens = tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = "estimated";
        }
        double cost = tokenEstimator.estimateCostUsd(promptTokens, completionTokens);
        long latency = System.currentTimeMillis() - start;
        observation.recordChat(agentType, "java-direct", latency, promptTokens, completionTokens, cost, traceId, tokenSource);
        if (runId > 0) {
            observation.completeRun(runId, "SUCCESS", latency, null, promptTokens, completionTokens, cost, tokenSource, null);
            budget.addCost(user, cost);
            observation.insertStep(runId, 1, "llm", "java-direct", sha(systemPrompt + userContent), sha(reply), System.currentTimeMillis() - llmStart, true);
            for (String tname : usedTools) {
                observation.insertToolCall(runId, null, tname, "{}", sha(tname), true, 0, CurrentUser.roles().isEmpty() ? "" : CurrentUser.roles().get(0));
            }
        }
        if (detail != null) observation.recordRag(detail.latencyMs(), detail.reranked());
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("reply", reply == null ? "" : reply);
        data.put("agentType", agentType);
        data.put("sessionId", sessionId);
        data.put("mode", "java-direct");
        data.put("promptVersion", promptVersion);
        data.put("traceId", traceId == null ? "" : traceId);
        data.put("flagged", flagged);
        data.put("tokenSource", tokenSource);
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        data.put("tools", usedTools);
        data.put("runId", runId);
        data.putAll(citationFlags);
        if (detail != null && detail.citations() != null) {
            var citList = new java.util.ArrayList<Map<String,Object>>();
            for (int ci=0; ci<detail.citations().size(); ci++) { var c=detail.citations().get(ci); citList.add(Map.of("idx", ci+1, "docId", c.docId(), "title", c.title(), "snippet", c.snippet(), "score", c.score())); }
            data.put("citations", citList);
        }
        return Result.ok(data);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(permitsPerMinute = 10, key = "agent-chat-stream")
    public ResponseEntity<SseEmitter> stream(@RequestBody Map<String, String> body,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        String agentType0 = body.getOrDefault("agentType", "general");
        boolean useDeep0 = "deep".equals(agentType0) || "true".equalsIgnoreCase(body.getOrDefault("useDeep", "false"));
        // 深度模式多步 ReAct 在真实推理模型下可远超 2 分钟；非深度保持 120s 快速失败
        SseEmitter emitter = new SseEmitter(useDeep0 ? 600_000L : 120_000L);
        // 禁止代理缓冲：SSE 的 confirm/首事件必须实时到达浏览器，nginx 等反代否则会攒缓冲
        // （dev 的 vite 代理对"毫秒级完成"的闸门快路径实测会吞到超时，prod nginx 同理有此风险）
        org.springframework.http.HttpHeaders sseHeaders = new org.springframework.http.HttpHeaders();
        sseHeaders.add("X-Accel-Buffering", "no");
        ResponseEntity<SseEmitter> emitterResponse = ResponseEntity.ok().headers(sseHeaders).body(emitter);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String capturedTrace = traceId;
        // 在请求线程解析并校验（SSE 线程池无 SecurityContext，username/sessionId 必须在进入线程前定妥）
        String raw = body.getOrDefault("message", "");
        String message = guard.sanitizeUserInput(raw);
        final String streamUser = com.smartsupply.common.CurrentUser.username();
        String rawSessionId = body.getOrDefault("sessionId", "default-" + streamUser);
        // final：SSE 线程池 lambda 捕获；"default" 归一化为按用户隔离的缺省会话
        final String sessionId = "default".equals(rawSessionId) ? "default-" + streamUser : rawSessionId;
        if (!memory.canAccess(sessionId, streamUser)) {
            try {
                emitter.send(SseEmitter.event().data("无权访问该会话").name("error"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitterResponse;
        }
        // 成本闸门（与 /chat 同口径）：超预算先拒绝，不产生任何 LLM/工具开销
        var budgetStatus = budget.check(streamUser);
        if (budgetStatus.over()) {
            final String budgetErr = budgetErrorJson(budgetStatus);
            sseExecutor.execute(() -> {
                try { emitter.send(SseEmitter.event().data(budgetErr).name("error")); emitter.complete(); }
                catch (Exception ignored) {}
            });
            return emitterResponse;
        }
        // 写意图闸门（java-direct 层兜底）：深度模式跳过——LangGraph 图内写工具执行前经
        // interrupt() 挂起等人工批准，HITL 决策基于模型真实工具调用而非消息措辞，更可靠；
        // resume 请求（批准/拒绝回调）同样不重复过闸门。
        boolean requireConfirm = "true".equalsIgnoreCase(body.getOrDefault("confirmCreate", "false"));
        final String resumeThreadId = String.valueOf(body.getOrDefault("threadId", "")).trim();
        final boolean isResume = "true".equalsIgnoreCase(body.getOrDefault("resume", "false")) && !resumeThreadId.isBlank();
        final boolean confirmApproved = "true".equalsIgnoreCase(body.getOrDefault("confirmApprove", "false"));
        // agent_type 服务端裁决（deep 是模式不是人设，人设按消息归类），归类结果进台账与 done 事件
        AgentTypeResolver.Resolved resolvedType = resolver.resolve(useDeep0 ? "auto" : agentType0, message);
        String agentType = resolvedType.agentType();
        boolean useDeep = useDeep0;
        if (isWriteIntent(message) && !requireConfirm && !isResume
                && !(useDeep && pythonSidecar.isEnabled())) {
            // 闸门事件也走异步线程发送：请求线程上同步 send+complete 的 SSE 字节在反代
            // （vite dev proxy / 部分中间层）链路上会被缓冲到超时，浏览器 30s 收不到
            // confirm 事件（E2E 实测踩坑）；与主链路保持同一线程模型。
            final String confirmJson;
            try {
                confirmJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                        "needConfirm", true,
                        "reply", "该操作将创建采购单（DRAFT，需人工审批）。请回复\"确认创建\"并再次提交。"));
            } catch (Exception ignored) {
                return emitterResponse;
            }
            sseExecutor.execute(() -> {
                try {
                    log.info("[gate-debug] sending confirm event (thread_id absent, java-direct write gate)");
                    emitter.send(SseEmitter.event().data(confirmJson).name("confirm"));
                    emitter.complete();
                    log.info("[gate-debug] confirm event sent & completed");
                } catch (Exception ignored) {}
            });
            return emitterResponse;
        }
        long streamStart = System.currentTimeMillis();
        sseExecutor.execute(() -> {
            try {
                MDC.put(TraceIdFilter.TRACE_ID, capturedTrace == null ? "stream" : capturedTrace);
                String systemPrompt = prompts.contentFor(agentType);
                // 单次赋值保持 effectively-final，供流式回调 lambda 捕获；RAG 触发与 /chat 同口径
                RagService.RecallDetail streamDetail = shouldRecall(agentType, message)
                        ? ragService.recallWithDetail(message) : null;
                final RagService.RecallDetail finalStreamDetail = streamDetail;
                String ragContext = finalStreamDetail == null ? "" : finalStreamDetail.context();
                final String finalUserContent = guard.wrapUserContent(message, ragContext);
                final String finalRagContext = ragContext;
                List<Message> history = memory.load(sessionId, systemPrompt);
                // 深度模式：走 Python LangGraph 边车 SSE，规划/取证事件实时转发（event:trace），
                // 回答切块下发（event:token）。此前深度分支是"阻塞等完整结果再一次性吐出"。
                if (useDeep && pythonSidecar.isEnabled()) {
                    List<Map<String, String>> msgs = new ArrayList<>();
                    if (isResume) {
                        // 恢复请求：图状态从边车 checkpoint 恢复，messages 被忽略（送空列表占位）
                    } else {
                        msgs.add(Map.of("role", "system", "content", systemPrompt));
                        for (Message m : history) {
                            String txt = m.getText();
                            if (txt == null || txt.equals(systemPrompt)) continue;
                            String role = (m instanceof org.springframework.ai.chat.messages.UserMessage) ? "user" : (m instanceof org.springframework.ai.chat.messages.AssistantMessage) ? "assistant" : "user";
                            msgs.add(Map.of("role", role, "content", txt));
                        }
                        msgs.add(Map.of("role", "user", "content", finalUserContent));
                    }
                    String deepTraceId = capturedTrace == null ? "" : capturedTrace;
                    Long uid = null;
                    try { uid = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, streamUser); } catch (Exception ignored) {}
                    long deepRunId = observation.insertRun(deepTraceId, streamUser, uid, sessionId, agentType, "python-deep",
                            chatMock ? "mock" : aiModelName, prompts.versionFor(agentType));
                    final long runIdFinal = deepRunId;
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> resumePayload = isResume ? Map.of("approved", confirmApproved) : null;
                    PythonSidecarService.SidecarResult sr = pythonSidecar.streamReason(msgs, agentType, sessionId, authorization, (eventName, payload) -> {
                        try {
                            switch (eventName) {
                                // 真流式：Python 侧按 provider token delta 逐片推送，Java 原样转发，不再切块伪装
                                case "reply_delta" -> {
                                    String delta = String.valueOf(payload.getOrDefault("text", ""));
                                    if (!delta.isEmpty()) emitter.send(SseEmitter.event().data(delta).name("token"));
                                }
                                // 兼容旧边车：整段 reply 到达时切块模拟流式
                                case "reply" -> {
                                    String text = String.valueOf(payload.getOrDefault("text", ""));
                                    for (int i = 0; i < text.length(); i += 64) {
                                        emitter.send(SseEmitter.event().data(text.substring(i, Math.min(i + 64, text.length()))).name("token"));
                                    }
                                }
                                case "tool", "write_tool" -> emitter.send(SseEmitter.event().data(om.writeValueAsString(payload)).name("trace"));
                                case "plan", "reflect" -> emitter.send(SseEmitter.event().data(om.writeValueAsString(payload)).name("trace"));
                                // 写闸门挂起：前端据此弹出批准/拒绝，携 threadId 以 resume 恢复
                                case "confirm_required" -> emitter.send(SseEmitter.event().data(om.writeValueAsString(payload)).name("confirm"));
                                default -> { }
                            }
                        } catch (Exception e) { log.warn("deep stream forward failed: {}", e.toString()); }
                    }, isResume ? resumeThreadId : "", resumePayload);
                    if (sr != null && sr.interrupted()) {
                        // 写闸门挂起：run 记 WAITING_CONFIRM；用户消息此刻入记忆（本轮对话已发生），
                        // 批准/拒绝后的完整回答由 resume 请求落记忆
                        if (!isResume) memory.append(sessionId, "user", message, streamUser);
                        if (runIdFinal > 0) {
                            observation.completeRun(runIdFinal, "WAITING_CONFIRM", System.currentTimeMillis() - streamStart,
                                    null, 0, 0, 0.0, "estimated", null);
                        }
                        Map<String, Object> done = new java.util.HashMap<>();
                        done.put("mode", "python-deep");
                        done.put("runId", runIdFinal);
                        done.put("interrupted", true);
                        done.put("threadId", sr.threadId());
                        if (!sr.confirm().isEmpty()) done.put("confirm", sr.confirm());
                        emitter.send(SseEmitter.event().data(om.writeValueAsString(done)).name("done"));
                        emitter.complete();
                        return;
                    }
                    if (sr != null && sr.reply() != null && !sr.reply().isBlank()) {
                        String deepReply = sr.reply();
                        if (!isResume) memory.append(sessionId, "user", message, streamUser);
                        memory.append(sessionId, "assistant", deepReply, streamUser);
                        long lat = System.currentTimeMillis() - streamStart;
                        DeepUsage du = deepUsage(sr, tokenEstimator.estimate(systemPrompt + finalUserContent) + estimateHistoryTokens(history), tokenEstimator.estimate(deepReply));
                        int pT = du.promptTokens();
                        int cT = du.completionTokens();
                        String tSrc = du.actual() ? "actual" : "estimated";
                        double deepCost = du.costUsd();
                        observation.recordChat(agentType, sr.degraded() ? "python-deep-degraded" : "python-deep",
                                lat, pT, cT, deepCost, deepTraceId, tSrc);
                        if (runIdFinal > 0) {
                            observation.updateRunMode(runIdFinal, sr.degraded() ? "python-deep-degraded" : "python-deep");
                            observation.completeRun(runIdFinal, sr.degraded() ? "DEGRADED" : "SUCCESS", lat, null, pT, cT,
                                    deepCost, tSrc, sr.degraded() ? sr.degradeReason() : null);
                            budget.addCost(streamUser, deepCost);
                            // 与 /chat 深度分支同口径：seq=1 为 llm 步骤，trace 从 2 起；成败位如实（provider 故障≠成功）
                            observation.insertStep(runIdFinal, 1, "llm", "python-sidecar", sha(systemPrompt), sha(deepReply),
                                    System.currentTimeMillis() - streamStart, !sr.degraded());
                            insertPythonTraceSteps(runIdFinal, sr.trace(), 2);
                            insertPythonToolCalls(runIdFinal, sr.toolResults(), om, firstRole());
                        }
                        List<String> tools = new ArrayList<>();
                        for (Map<String, Object> t : sr.toolResults()) tools.add(String.valueOf(t.getOrDefault("tool", "")));
                        Map<String, Object> done = new java.util.HashMap<>();
                        done.put("mode", "python-deep");
                        done.put("totalMs", lat);
                        done.put("runId", runIdFinal);
                        done.put("degraded", sr.degraded());
                        if (sr.degraded()) done.put("degradeReason", sr.degradeReason());
                        done.put("tools", tools);
                        emitter.send(SseEmitter.event().data(om.writeValueAsString(done)).name("done"));
                        emitter.complete();
                        return;
                    }
                    // 深度失败：不静默假装深度成功，显式告知后回退 java-direct 流式（台账 runId 作废）
                    if (runIdFinal > 0) observation.completeRun(runIdFinal, "FAILED", System.currentTimeMillis() - streamStart, null, 0, 0, 0.0, "estimated", "sidecar stream unavailable");
                    emitter.send(SseEmitter.event().data("[深度推理暂不可用，已回退标准模式]").name("notice"));
                }
                var spec = chatClient.prompt().system(systemPrompt);
                for (Message m : history) {
                    if (m.getText() == null || m.getText().equals(systemPrompt)) continue;
                    spec = spec.messages(m);
                }
                StringBuilder acc = new StringBuilder();
                AtomicBoolean firstToken = new AtomicBoolean(true);
                AtomicLong ttfbMs = new AtomicLong(-1);
                com.smartsupply.agent.tools.ToolSecurity.beginToolTrace();
                try {
                    var stream = spec.user(finalUserContent)
                            .tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                            .stream().content();
                    stream.subscribe(
                            chunk -> {
                                try {
                                    if (chunk != null) {
                                        if (firstToken.compareAndSet(true, false)) {
                                            long v = System.currentTimeMillis() - streamStart;
                                            ttfbMs.set(v);
                                            observation.recordTtfb(agentType, "stream", v);
                                        }
                                        acc.append(chunk);
                                        emitter.send(SseEmitter.event().data(chunk).name("token"));
                                    }
                                } catch (Exception e) { emitter.completeWithError(e); }
                            },
                            err -> emitter.completeWithError(err),
                            () -> {
                                try {
                                    String reply = enforceCitation(acc.toString(), finalRagContext, citationTitles(finalStreamDetail), null);
                                    if (reply.isBlank()) {
                                        String fallback = chatClient.prompt().system(systemPrompt)
                                                .user(finalUserContent).tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                                                .call().content();
                                        fallback = enforceCitation(fallback == null ? "" : fallback, finalRagContext, citationTitles(finalStreamDetail), null);
                                        reply = fallback;
                                        sendTokenized(emitter, reply, 64);
                                    }
                                    memory.append(sessionId, "user", message, streamUser);
                                    memory.append(sessionId, "assistant", reply, streamUser);
                                    long totalMs = System.currentTimeMillis() - streamStart;
                                    // 一次性消费 usage：此前三个 consumeLast* 分三次消费 ThreadLocal/静态值，
                                    // 并发下会把 A 请求的 token 记到 B 请求头上
                                    TokenContext.Usage usage2 = MuseSparkChatModel.consumeUsage();
                                    int pTokens = usage2 != null && usage2.promptTokens() > 0
                                            ? usage2.promptTokens() : tokenEstimator.estimate(systemPrompt + finalUserContent) + estimateHistoryTokens(history);
                                    int cTokens = usage2 != null && usage2.completionTokens() > 0
                                            ? usage2.completionTokens() : tokenEstimator.estimate(reply);
                                    String srcFinal = usage2 != null && "actual".equals(usage2.source()) ? "actual" : "estimated";
                                    double cost2 = tokenEstimator.estimateCostUsd(pTokens, cTokens);
                                    observation.recordChat(agentType, "stream", totalMs, pTokens, cTokens, cost2, capturedTrace == null ? "stream" : capturedTrace, srcFinal);
                                    Map<String, Object> done = new java.util.HashMap<>();
                                    done.put("ttfbMs", ttfbMs.get());
                                    done.put("totalMs", totalMs);
                                    done.put("tokenSource", srcFinal);
                                    done.put("tools", com.smartsupply.agent.tools.ToolSecurity.endToolTrace());
                                    emitter.send(SseEmitter.event().data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(done)).name("done"));
                                    emitter.complete();
                                } catch (Exception e) { emitter.completeWithError(e); }
                            }
                    );
                    return;
                } catch (Exception ex) {
                    String reply = enforceCitation(spec.user(finalUserContent).tools(inventoryTools, purchaseTools, contractTools, catalogTools).call().content(), finalRagContext, citationTitles(finalStreamDetail), null);
                    String text = reply == null ? "" : reply;
                    memory.append(sessionId, "user", message, streamUser);
                    memory.append(sessionId, "assistant", text, streamUser);
                    sendTokenized(emitter, text, 64);
                    long totalMs = System.currentTimeMillis() - streamStart;
                    observation.recordChat(agentType, "stream-fallback", totalMs, tokenEstimator.estimate(systemPrompt + finalUserContent), tokenEstimator.estimate(text), tokenEstimator.estimateCostUsd(tokenEstimator.estimate(text), tokenEstimator.estimate(text)), capturedTrace == null ? "stream" : capturedTrace, "estimated");
                    emitter.send(SseEmitter.event().data("[DONE]").name("done"));
                    emitter.complete();
                }
            } catch (Exception e) { try { emitter.completeWithError(e); } catch (Exception ignored) {} }
            finally { MDC.remove(TraceIdFilter.TRACE_ID); }
        });
        return emitterResponse;
    }

    @GetMapping("/mode")
    public Result<Map<String, Object>> mode() {
        boolean enabled = pythonSidecar.isEnabled();
        return Result.ok(Map.of(
                "pythonSidecarEnabled", enabled,
                "javaMode", "direct",
                "arch", "Java业务编排 + Python LangGraph深度推理(ReAct)",
                // 前端据此显示 Mock 横幅；mock 态的"RAG"是哈希伪向量，不具语义，必须显式可见
                "chatMock", chatMock,
                "embeddingMock", embeddingMock));
    }

    @GetMapping("/tools")
    public Result<List<Map<String, String>>> tools() {
        List<Map<String, String>> list = List.of(
                Map.of("name", "getInventory", "desc", "查询SKU库存与安全库存(参数化+限流)"),
                Map.of("name", "listLowStock", "desc", "查询所有低于安全库存的SKU"),
                Map.of("name", "listSuppliers", "desc", "查询供应商列表"),
                Map.of("name", "createPurchaseOrder", "desc", "创建采购单(HITL需二次确认+鉴权+幂等)"),
                Map.of("name", "searchContracts", "desc", "搜索合同(参数化ILIKE)"),
                Map.of("name", "getContractRisk", "desc", "查询合同风控报告"),
                Map.of("name", "searchCatalog", "desc", "搜索商品与SKU(参数化)")
        );
        return Result.ok(list);
    }

    @GetMapping("/prompts")
    public Result<Map<String, Object>> prompts() {
        Map<String, com.smartsupply.config.PromptRegistry.PromptVersion> all = prompts.all();
        return Result.ok(Map.of("prompts", all));
    }

    @GetMapping("/memory/{sessionId}")
    public Result<Map<String, Object>> memoryView(@PathVariable String sessionId) {
        // 会话隔离统一裁决（与 /chat、/chat/stream 同一 canAccess）：无主可读，仅归属者/ADMIN 可读他人
        String viewer = com.smartsupply.common.CurrentUser.username();
        if (!memory.canAccess(sessionId, viewer) && !com.smartsupply.common.CurrentUser.hasRole("ADMIN")) {
            return Result.fail(403, "无权查看他人会话");
        }
        String owner = memory.sessionOwner(sessionId);
        ChatMemoryService.MemorySnapshot snap = memory.snapshot(sessionId, prompts.contentFor("general"));
        return Result.ok(Map.of("sessionId", sessionId, "owner", owner == null ? "" : owner, "size", snap.messages().size(), "summary", snap.summary() == null ? "" : snap.summary(), "messages", snap.messages()));
    }

    /** 用户级运行轨迹：本人（或 ADMIN）可查看某次 run 的规划步骤与工具调用。
     *  此前轨迹仅 ADMIN 可见（AdminAgentController），普通用户对话完全看不到 agent 如何取证推理。 */
    @GetMapping("/runs/{runId}/trace")
    public Result<Map<String, Object>> runTrace(@PathVariable long runId) {
        String user = com.smartsupply.common.CurrentUser.username();
        List<Map<String, Object>> runs;
        try {
            runs = jdbc.queryForList("SELECT id,trace_id,username,session_id,agent_type,mode,status,latency_ms,total_tokens,cost_usd,error_msg FROM agent_run WHERE id=?", runId);
        } catch (Exception e) {
            return Result.fail(500, "轨迹查询失败");
        }
        if (runs.isEmpty()) return Result.fail(404, "run 不存在");
        Map<String, Object> run = runs.get(0);
        if (!java.util.Objects.equals(String.valueOf(run.get("username")), user) && !com.smartsupply.common.CurrentUser.hasRole("ADMIN")) {
            return Result.fail(403, "无权查看他人运行轨迹");
        }
        List<Map<String, Object>> steps = jdbc.queryForList("SELECT seq,node,name,success,latency_ms FROM agent_step WHERE run_id=? ORDER BY seq", runId);
        List<Map<String, Object>> tools = jdbc.queryForList("SELECT tool,args_json,success FROM agent_tool_call WHERE run_id=? ORDER BY id", runId);
        return Result.ok(Map.of("run", run, "steps", steps, "toolCalls", tools));
    }

    @GetMapping("/metrics/summary")
    public Result<Map<String, Object>> metricsSummary() {        io.micrometer.core.instrument.MeterRegistry reg = observation.getRegistry();
        Map<String, Object> out = new java.util.HashMap<>();
        for (String n : List.of("agent.chat.latency", "agent.chat.ttft", "agent.tool.latency", "rag.recall.latency", "rag.rerank.count")) {
            try {
                for (io.micrometer.core.instrument.Meter m : reg.find(n).meters()) {
                    String key = n + m.getId().getTags().toString();
                    if (m instanceof Timer t) out.put(key, Map.of("count", t.count(), "meanMs", t.mean(java.util.concurrent.TimeUnit.MILLISECONDS), "maxMs", t.max(java.util.concurrent.TimeUnit.MILLISECONDS)));
                    else if (m instanceof Counter c) out.put(key, c.count());
                }
            } catch (Exception ignored) {}
        }
        try {
            for (io.micrometer.core.instrument.Meter m : reg.find("agent.chat.tokens").meters()) out.put("tokens" + m.getId().getTags(), ((Counter)m).count());
            for (io.micrometer.core.instrument.Meter m : reg.find("agent.chat.cost_usd").meters()) out.put("cost" + m.getId().getTags(), ((Counter)m).count());
            for (io.micrometer.core.instrument.Meter m : reg.find("agent.chat.count").meters()) out.put("count" + m.getId().getTags(), ((Counter)m).count());
        } catch (Exception ignored) {}
        return Result.ok(out);
    }

    /**
     * 写意图检测（HITL 确认闸门的 UX 层；硬约束在工具层：requireSupplierWritePerm+DRAFT+幂等）：
     * 1) 关键词命中 → 直接确认（零成本、覆盖明确措辞，测试确定性也依赖这条主路径）；
     * 2) 含采购动作词汇但关键词未命中 → LLM 分类器兜底（覆盖"帮我订500件"等变体）；
     *    纯信息类消息（不含动作词汇）不触发分类器，避免每条读查询多一次 LLM 调用；
     * 3) 分类器失败 → 按动作词汇子集保守判定（宁可误报多弹确认，不可漏报绕过 HITL；
     *    但不含"订/采购/PO/place"等强动作词的消息不拦截，避免误伤"查看采购单状态"类读查询）。
     */
    boolean isWriteIntent(String msg) {
        String lower = msg.toLowerCase();
        boolean regexHit = lower.contains("创建采购") || lower.contains("下单") || lower.contains("下个单") || lower.contains("下订单")
                || lower.contains("帮我订") || lower.contains("订购") || lower.contains("生成采购") || lower.contains("新建采购")
                || lower.contains("createpurchase") || lower.contains("create purchase") || lower.contains("create a purchase")
                || lower.contains("place order") || lower.contains("place an order")
                || lower.contains("直接创建");
        if (regexHit) return true;
        boolean actionVocab = lower.contains("采购") || lower.contains("订") || lower.contains("purchase")
                || lower.contains("order") || lower.contains("补单") || lower.contains("po");
        if (!actionVocab) return false;
        try {
            String ans = chatClient.prompt()
                    .system("你是意图分类器。判断用户消息是否要求【执行】写操作（创建/提交采购单、下单订购）。仅陈述事实、查询状态、询问建议的回答 NO；明确要求执行动作的回答 YES。只输出 YES 或 NO。")
                    .user(msg).call().content();
            if (ans != null && ans.trim().toUpperCase().startsWith("YES")) return true;
            if (ans == null || ans.trim().isEmpty()) throw new IllegalStateException("empty classifier output");
            return false;
        } catch (Exception e) {
            log.warn("write-intent classifier failed, conservative fallback: {}", e.toString());
            // fail-closed：分类器故障时按强动作词保守判定
            return lower.contains("下个单") || lower.contains("帮我下") || lower.contains("订") || lower.contains("purchase order") || lower.contains("创建");
        }
    }

    /**
     * 引用兜底：回答没有引用且本轮有召回时，列出"检索候选来源"供人工核对。
     * 诚实性边界：这些标题只是检索命中，不代表模型作答真正采用了它们——文案必须写明
     * "未必被采用"，并以 citationMissing 标志暴露给前端/台账（此前把召回片段伪装成回答依据，已移除）。
     */
    private String enforceCitation(String reply, String ragContext, List<String> citationTitles, Map<String, Object> dataOut) {
        if (reply == null || reply.isBlank()) return reply == null ? "" : reply;
        if (ragContext == null || ragContext.isBlank()) return reply;
        if (reply.contains("[引用]") || reply.contains("[候选引用]") || reply.contains("依据不足")) return reply;
        if (citationTitles == null || citationTitles.isEmpty()) return reply;
        StringBuilder sb = new StringBuilder(reply).append("\n\n[候选引用] 本轮检索命中的来源（仅供人工核对，不代表本回答已逐条采用；结构化来源见响应 citations 字段）：");
        citationTitles.stream().limit(3).forEach(t -> sb.append("《").append(t).append("》"));
        if (dataOut != null) dataOut.put("citationMissing", true);
        return sb.toString();
    }

    private List<String> citationTitles(RagService.RecallDetail detail) {
        if (detail == null || detail.citations() == null) return List.of();
        return detail.citations().stream().map(c -> c.title()).toList();
    }

    /** 深度模式用量核算：优先按模型拆分计成本（usage_total.by_model，多模型路由下口径才真实），
     *  旧版边车无拆分时回退单模型口径。estPrompt/estCompletion 为边车未回传用量时的本地估算。 */
    private record DeepUsage(int promptTokens, int completionTokens, double costUsd, boolean actual) {}    private DeepUsage deepUsage(PythonSidecarService.SidecarResult sr, int estPrompt, int estCompletion) {
        if (sr != null && sr.usageByModel() != null && !sr.usageByModel().isEmpty()) {
            int p = 0, c = 0;
            double cost = 0;
            boolean allActual = true;
            for (PythonSidecarService.ModelUsage mu : sr.usageByModel()) {
                p += mu.promptTokens();
                c += mu.completionTokens();
                cost += tokenEstimator.estimateCostUsd(mu.model(), mu.promptTokens(), mu.completionTokens());
                if (!"actual".equals(mu.source())) allActual = false;
            }
            if (p + c > 0) return new DeepUsage(p, c, cost, allActual);
        }
        PythonSidecarService.TokenUsage su = sr == null ? null : sr.usage();
        int pT = su != null && su.promptTokens() > 0 ? su.promptTokens() : estPrompt;
        int cT = su != null && su.completionTokens() > 0 ? su.completionTokens() : estCompletion;
        boolean actual = su != null && "actual".equals(su.source());
        return new DeepUsage(pT, cT, tokenEstimator.estimateCostUsd(pT, cT), actual);
    }

    private String budgetErrorJson(com.smartsupply.agent.cost.AgentBudgetService.BudgetStatus st) {
        String msg = "今日 Agent 成本预算已用尽（" + st.describe() + "）。请联系管理员调整限额或明日再试。";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("error", msg));
        } catch (Exception ignored) {
            return "{\"error\":\"daily agent budget exceeded\"}";
        }
    }

    /** RAG 触发统一（/chat 与 /chat/stream 必须同口径）：contract 人设恒召回；消息含合同/风控/风险词召回。
     *  此前 stream 只查"合同"，chat 还查"风控/风险"，同一问题在两条路径下检索行为不同。 */
    private static boolean shouldRecall(String agentType, String message) {
        return "contract".equals(agentType) || message.contains("合同") || message.contains("风控") || message.contains("风险");
    }

    /** 分块下发 token 事件：此前兜底路径 reply.split("") 逐字符 send（每个汉字一个 SSE 帧，千级事件）。
     *  分块保持流式观感，事件数降两个数量级。 */
    private static void sendTokenized(SseEmitter emitter, String text, int chunkSize) throws Exception {
        if (text == null || text.isEmpty()) return;
        for (int i = 0; i < text.length(); i += chunkSize) {
            emitter.send(SseEmitter.event().data(text.substring(i, Math.min(i + chunkSize, text.length()))).name("token"));
        }
    }

    /** 历史消息 token 估算：此前用 history.size()*50 魔法数（与"成本精确到分"的台账口径矛盾）。
     *  按真实文本逐条估算，与 TokenEstimator 单一口径对齐。 */
    private int estimateHistoryTokens(List<Message> history) {
        int t = 0;
        for (Message m : history) {
            t += tokenEstimator.estimate(m.getText() == null ? "" : m.getText());
        }
        return t;
    }

    /** python trace 条目 → 步骤成败：provider 为 unavailable/error 视为失败。
     *  此前落库一律 success=true，节点级失败信息进了台账又变回全绿，观测失真。 */
    private static boolean stepOkFromTrace(Map<String, Object> tr) {
        String provider = String.valueOf(tr.getOrDefault("provider", ""));
        return !provider.equals("unavailable") && !provider.equals("error");
    }

    /** 深度模式 trace 落 agent_step（/chat 与 /chat/stream 共用，消除双份漂移的落库逻辑）。 */
    private void insertPythonTraceSteps(long runId, List<Map<String, Object>> trace, int startSeq) {
        int seq = startSeq;
        for (Map<String, Object> tr : trace) {
            Object stepName = tr.getOrDefault("tool", tr.getOrDefault("provider", tr.getOrDefault("name", "")));
            observation.insertStep(runId, seq++, String.valueOf(tr.getOrDefault("node", "step")),
                    String.valueOf(stepName), null, null, 0, stepOkFromTrace(tr));
        }
    }

    /** 深度模式工具调用落 agent_tool_call（/chat 与 /chat/stream 共用）；userRole 统一为首角色（列语义为角色）。 */
    private void insertPythonToolCalls(long runId, List<Map<String, Object>> toolResults,
                                       com.fasterxml.jackson.databind.ObjectMapper om, String userRole) {
        for (Map<String, Object> tcr : toolResults) {
            Object args = tcr.get("args");
            String argsJson;
            try { argsJson = args == null ? "{}" : om.writeValueAsString(args); }
            catch (Exception e) { argsJson = String.valueOf(args); }
            boolean toolOk = !Boolean.FALSE.equals(tcr.get("ok"));
            observation.insertToolCall(runId, null, String.valueOf(tcr.getOrDefault("tool", "tool")), argsJson,
                    sha(String.valueOf(tcr.get("result"))), toolOk, 0, userRole);
        }
    }

    private static String firstRole() {
        return CurrentUser.roles().isEmpty() ? "" : CurrentUser.roles().get(0);
    }

    private static String sha(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(s == null ? 0 : s.length()); }
    }
}
