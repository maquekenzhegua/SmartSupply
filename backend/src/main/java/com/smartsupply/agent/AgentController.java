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
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
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
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-sse");
        t.setDaemon(true);
        return t;
    });

    public AgentController(ChatClient chatClient, RagService ragService, ChatMemoryService memory,
                           com.smartsupply.agent.tools.InventoryTools inventoryTools,
                           com.smartsupply.agent.tools.PurchaseTools purchaseTools,
                           com.smartsupply.agent.tools.ContractTools contractTools,
                           com.smartsupply.agent.tools.CatalogTools catalogTools,
                           PythonSidecarService pythonSidecar, PromptRegistry prompts, ObservationService observation,
                           PromptGuard guard, TokenEstimator tokenEstimator, org.springframework.jdbc.core.JdbcTemplate jdbc) {
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
    }

    @PostMapping("/chat")
    @RateLimit(permitsPerMinute = 30, key = "agent-chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        long start = System.currentTimeMillis();
        String rawMessage = body.getOrDefault("message", "");
        String message = guard.sanitizeUserInput(rawMessage);
        boolean flagged = guard.containsInjection(rawMessage);
        String agentType = body.getOrDefault("agentType", "general");
        final String user = com.smartsupply.common.CurrentUser.username();
        String rawSessionId = body.getOrDefault("sessionId", "default-" + user);
        // "default" 归一化为按用户隔离的缺省会话；final 以便后续 lambda 捕获
        final String sessionId = "default".equals(rawSessionId) ? "default-" + user : rawSessionId;
        if (!memory.canAccess(sessionId, user)) {
            return Result.fail(403, "无权访问该会话");
        }
        boolean requireConfirm = "true".equalsIgnoreCase(body.getOrDefault("confirmCreate", "false"));
        if (message.isBlank()) return Result.fail(400, "message 不能为空");
        if (isWriteIntent(message) && !requireConfirm) {
            return Result.ok(Map.of("reply", "该操作将创建采购单（DRAFT，需人工审批）。请回复\"确认创建\"并再次提交，或在界面点击二次确认。", "agentType", agentType, "sessionId", sessionId, "needConfirm", true, "flagged", flagged));
        }
        String systemPrompt = prompts.contentFor(agentType);
        String promptVersion = prompts.versionFor(agentType);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        if (traceId == null || traceId.isBlank()) traceId = com.smartsupply.common.TraceContext.get();
        if (traceId == null) traceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String ragContext = "";
        RagService.RecallDetail detail = null;
        if ("contract".equals(agentType) || message.contains("合同") || message.contains("风控") || message.contains("风险")) {
            detail = ragService.recallWithDetail(message);
            ragContext = detail.context();
        }
        List<Message> history = memory.load(sessionId, systemPrompt);
        String userContent = guard.wrapUserContent(message, ragContext);
        // persistent run
        Long uid = null; try { uid = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, user); } catch (Exception ignored) {}
        String modelName = prompts.versionFor(agentType);
        long runId = observation.insertRun(traceId, user, uid, sessionId, agentType, "java-direct", modelName);
        boolean useDeep = "deep".equals(agentType) || "true".equalsIgnoreCase(body.getOrDefault("useDeep", "false"));
        if (useDeep && pythonSidecar.isEnabled()) {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", systemPrompt));
            for (Message m : history) {
                String txt = m.getText();
                if (txt == null || txt.equals(systemPrompt)) continue;
                String role = (m instanceof org.springframework.ai.chat.messages.UserMessage) ? "user" : (m instanceof org.springframework.ai.chat.messages.AssistantMessage) ? "assistant" : "user";
                msgs.add(Map.of("role", role, "content", txt));
            }
            msgs.add(Map.of("role", "user", "content", userContent));
            long sidecarStart = System.currentTimeMillis();
            PythonSidecarService.SidecarResult sr = pythonSidecar.reasonStructured(msgs, agentType, sessionId);
            if (sr != null && sr.reply() != null && !sr.reply().isBlank()) {
                String deepReply = sr.reply();
                memory.append(sessionId, "user", message, user);
                memory.append(sessionId, "assistant", deepReply, user);
                int promptTokens = tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
                int completionTokens = tokenEstimator.estimate(deepReply);
                double cost = tokenEstimator.estimateCostUsd(promptTokens, completionTokens);
                long lat = System.currentTimeMillis() - start;
                observation.recordChat(agentType, "python-deep", lat, promptTokens, completionTokens, cost, traceId, "estimated");
                if (runId > 0) {
                    observation.completeRun(runId, "SUCCESS", lat, null, promptTokens, completionTokens, cost, "estimated", null);
                    observation.insertStep(runId, 1, "llm", "python-sidecar", sha(systemPrompt), sha(deepReply), System.currentTimeMillis() - sidecarStart, true);
                    int seq = 2;
                    for (Map<String, Object> tr : sr.trace()) {
                        observation.insertStep(runId, seq++, String.valueOf(tr.getOrDefault("node", "step")), String.valueOf(tr.getOrDefault("name", "")), null, null, 0, true);
                    }
                    for (Map<String, Object> tcr : sr.toolResults()) {
                        observation.insertToolCall(runId, null, String.valueOf(tcr.getOrDefault("tool", "tool")), String.valueOf(tcr.get("args")), sha(String.valueOf(tcr.get("result"))), true, 0, CurrentUser.roles().isEmpty() ? "" : CurrentUser.roles().get(0));
                    }
                }
                if (detail != null) observation.recordRag(detail.latencyMs(), detail.reranked());
                return Result.ok(Map.of("reply", deepReply, "agentType", agentType, "sessionId", sessionId, "mode", "python-deep", "via", "langgraph", "promptVersion", promptVersion, "traceId", traceId, "flagged", flagged, "tokenSource", "estimated", "runId", runId));
            }
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
        try {
            reply = spec.user(userContent)
                    .tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                    .call().content();
        } finally {
            usedTools = com.smartsupply.agent.tools.ToolSecurity.endToolTrace();
        }
        reply = enforceCitation(reply, ragContext);
        memory.append(sessionId, "user", message, user);
        // persist tool calls json
        try {
            if (!usedTools.isEmpty()) {
                Long sid = jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? ORDER BY id DESC LIMIT 1", Long.class, sessionId);
                if (sid != null) {
                    String toolJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(usedTools);
                    jdbc.update("UPDATE chat_message SET tool_calls_json=? WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1", toolJson, sid);
                }
            }
        } catch (Exception ignored) {}
        TokenContext.Usage usage = MuseSparkChatModel.consumeUsage();
        int promptTokens;
        int completionTokens;
        String tokenSource;
        if (usage != null) {
            promptTokens = usage.promptTokens() > 0 ? usage.promptTokens() : tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
            completionTokens = usage.completionTokens() > 0 ? usage.completionTokens() : tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = usage.source();
        } else {
            promptTokens = tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
            completionTokens = tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = "estimated";
        }
        double cost = tokenEstimator.estimateCostUsd(promptTokens, completionTokens);
        long latency = System.currentTimeMillis() - start;
        observation.recordChat(agentType, "java-direct", latency, promptTokens, completionTokens, cost, traceId, tokenSource);
        if (runId > 0) {
            observation.completeRun(runId, "SUCCESS", latency, null, promptTokens, completionTokens, cost, tokenSource, null);
            observation.insertStep(runId, 1, "llm", "java-direct", sha(systemPrompt + userContent), sha(reply), System.currentTimeMillis() - llmStart, true);
            for (String tname : usedTools) {
                observation.insertToolCall(runId, null, tname, "{}", sha(tname), true, 0, CurrentUser.roles().isEmpty() ? "" : CurrentUser.roles().get(0));
            }
        }
        if (detail != null) observation.recordRag(detail.latencyMs(), detail.reranked());
        // enforce [n] citation if rag present but reply lacks it
        if (detail != null && detail.citations() != null && !detail.citations().isEmpty() && reply != null && !reply.contains("[")) {
            StringBuilder cb = new StringBuilder(reply);
            cb.append("\n\n");
            for (int ci=0; ci<detail.citations().size() && ci<3; ci++) {
                var c = detail.citations().get(ci);
                cb.append("[").append(ci+1).append("] ").append(c.title()).append(" | ").append(c.snippet()).append("\n");
            }
            reply = cb.toString();
        }
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
        if (detail != null && detail.citations() != null) {
            var citList = new java.util.ArrayList<Map<String,Object>>();
            for (int ci=0; ci<detail.citations().size(); ci++) { var c=detail.citations().get(ci); citList.add(Map.of("idx", ci+1, "docId", c.docId(), "title", c.title(), "snippet", c.snippet(), "score", c.score())); }
            data.put("citations", citList);
        }
        return Result.ok(data);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String capturedTrace = traceId;
        // 在请求线程解析并校验（SSE 线程池无 SecurityContext，username/sessionId 必须在进入线程前定妥）
        String raw = body.getOrDefault("message", "");
        String message = guard.sanitizeUserInput(raw);
        String agentType = body.getOrDefault("agentType", "general");
        final String streamUser = com.smartsupply.common.CurrentUser.username();
        String rawSessionId = body.getOrDefault("sessionId", "default-" + streamUser);
        // final：SSE 线程池 lambda 捕获；"default" 归一化为按用户隔离的缺省会话
        final String sessionId = "default".equals(rawSessionId) ? "default-" + streamUser : rawSessionId;
        if (!memory.canAccess(sessionId, streamUser)) {
            try {
                emitter.send(SseEmitter.event().data("无权访问该会话").name("error"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        long streamStart = System.currentTimeMillis();
        sseExecutor.execute(() -> {
            try {
                MDC.put(TraceIdFilter.TRACE_ID, capturedTrace == null ? "stream" : capturedTrace);
                String systemPrompt = prompts.contentFor(agentType);
                String ragContext = "";
                if ("contract".equals(agentType) || message.contains("合同")) ragContext = ragService.recall(message);
                final String finalUserContent = guard.wrapUserContent(message, ragContext);
                final String finalRagContext = ragContext;
                List<Message> history = memory.load(sessionId, systemPrompt);
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
                                    String reply = enforceCitation(acc.toString(), finalRagContext);
                                    if (reply.isBlank()) {
                                        String fallback = chatClient.prompt().system(systemPrompt)
                                                .user(finalUserContent).tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                                                .call().content();
                                        fallback = enforceCitation(fallback == null ? "" : fallback, finalRagContext);
                                        reply = fallback;
                                        for (String ch : reply.split("")) emitter.send(SseEmitter.event().data(String.valueOf(ch)).name("token"));
                                    }
                                    memory.append(sessionId, "user", message, streamUser);
                                    memory.append(sessionId, "assistant", reply, streamUser);
                                    long totalMs = System.currentTimeMillis() - streamStart;
                                    long pt2 = MuseSparkChatModel.consumeLastPromptTokens();
                                    long ct2 = MuseSparkChatModel.consumeLastCompletionTokens();
                                    String src2 = MuseSparkChatModel.consumeLastSource();
                                    int pTokens = pt2 > 0 ? (int) pt2 : tokenEstimator.estimate(systemPrompt + finalUserContent + history.size() * 200);
                                    int cTokens = ct2 > 0 ? (int) ct2 : tokenEstimator.estimate(reply);
                                    double cost2 = tokenEstimator.estimateCostUsd(pTokens, cTokens);
                                    String srcFinal = (pt2 > 0 || ct2 > 0) ? src2 : "estimated";
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
                    String reply = enforceCitation(spec.user(finalUserContent).tools(inventoryTools, purchaseTools, contractTools, catalogTools).call().content(), finalRagContext);
                    String text = reply == null ? "" : reply;
                    memory.append(sessionId, "user", message, streamUser);
                    memory.append(sessionId, "assistant", text, streamUser);
                    for (String ch : text.split("")) { emitter.send(SseEmitter.event().data(String.valueOf(ch)).name("token")); }
                    long totalMs = System.currentTimeMillis() - streamStart;
                    observation.recordChat(agentType, "stream-fallback", totalMs, tokenEstimator.estimate(systemPrompt + finalUserContent), tokenEstimator.estimate(text), tokenEstimator.estimateCostUsd(tokenEstimator.estimate(text), tokenEstimator.estimate(text)), capturedTrace == null ? "stream" : capturedTrace, "estimated");
                    emitter.send(SseEmitter.event().data("[DONE]").name("done"));
                    emitter.complete();
                }
            } catch (Exception e) { try { emitter.completeWithError(e); } catch (Exception ignored) {} }
            finally { MDC.remove(TraceIdFilter.TRACE_ID); }
        });
        return emitter;
    }

    @GetMapping("/mode")
    public Result<Map<String, Object>> mode() {
        boolean enabled = pythonSidecar.isEnabled();
        return Result.ok(Map.of("pythonSidecarEnabled", enabled, "javaMode", "direct", "arch", "Java业务编排 + Python LangGraph深度推理(ReAct)"));
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

    @GetMapping("/metrics/summary")
    public Result<Map<String, Object>> metricsSummary() {
        io.micrometer.core.instrument.MeterRegistry reg = observation.getRegistry();
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

    private boolean isWriteIntent(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("创建采购") || lower.contains("下单") || lower.contains("createpurchase") || lower.contains("create purchase") || lower.contains("帮我下单") || lower.contains("直接创建");
    }

    private String enforceCitation(String reply, String ragContext) {
        if (reply == null) return "";
        if (ragContext == null || ragContext.isBlank()) return reply;
        if (reply.contains("依据不足") || reply.contains("引用") || reply.contains("knowledge")) return reply;
        String hint = guard.citationInstruction(ragContext);
        if (hint.contains("无召回")) return reply;
        String snippet = ragContext.length() > 120 ? ragContext.substring(0, 120).replace("\n", " ") + "..." : ragContext.replace("\n", " ");
        return reply + "\n\n[引用] 依据 knowledge 召回: " + snippet;
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
