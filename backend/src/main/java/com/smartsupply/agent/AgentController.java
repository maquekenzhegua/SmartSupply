package com.smartsupply.agent;

import com.smartsupply.agent.memory.ChatMemoryService;
import com.smartsupply.agent.rag.RagService;
import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import com.smartsupply.common.TraceIdFilter;
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
                           PromptGuard guard, TokenEstimator tokenEstimator) {
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
    }

    @PostMapping("/chat")
    @RateLimit(permitsPerMinute = 30, key = "agent-chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        long start = System.currentTimeMillis();
        String rawMessage = body.getOrDefault("message", "");
        String message = guard.sanitizeUserInput(rawMessage);
        boolean flagged = guard.containsInjection(rawMessage);
        String agentType = body.getOrDefault("agentType", "general");
        String sessionId = body.getOrDefault("sessionId", "default");
        boolean requireConfirm = "true".equalsIgnoreCase(body.getOrDefault("confirmCreate", "false"));
        if (message.isBlank()) return Result.fail(400, "message 不能为空");
        if (isWriteIntent(message) && !requireConfirm) {
            return Result.ok(Map.of("reply", "该操作将创建采购单（DRAFT，需人工审批）。请回复\"确认创建\"并再次提交，或在界面点击二次确认。", "agentType", agentType, "sessionId", sessionId, "needConfirm", true, "flagged", flagged));
        }
        String systemPrompt = prompts.contentFor(agentType);
        String promptVersion = prompts.versionFor(agentType);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String ragContext = "";
        RagService.RecallDetail detail = null;
        if ("contract".equals(agentType) || message.contains("合同") || message.contains("风控") || message.contains("风险")) {
            detail = ragService.recallWithDetail(message);
            ragContext = detail.context();
        }
        List<Message> history = memory.load(sessionId, systemPrompt);
        String userContent = guard.wrapUserContent(message, ragContext);
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
            String deepReply = pythonSidecar.reason(msgs, agentType, sessionId);
            if (deepReply != null && !deepReply.isBlank()) {
                memory.append(sessionId, "user", message);
                memory.append(sessionId, "assistant", deepReply);
                int promptTokens = tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
                int completionTokens = tokenEstimator.estimate(deepReply);
                double cost = tokenEstimator.estimateCostUsd(promptTokens, completionTokens);
                observation.recordChat(agentType, "python-deep", System.currentTimeMillis() - start, promptTokens, completionTokens, cost, traceId, "estimated");
                if (detail != null) observation.recordRag(detail.latencyMs(), detail.reranked());
                return Result.ok(Map.of("reply", deepReply, "agentType", agentType, "sessionId", sessionId, "mode", "python-deep", "via", "langgraph", "promptVersion", promptVersion, "traceId", traceId == null ? "" : traceId, "flagged", flagged, "tokenSource", "estimated"));
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
        try {
            reply = spec.user(userContent)
                    .tools(inventoryTools, purchaseTools, contractTools, catalogTools)
                    .call().content();
        } finally {
            // 即使工具抛异常也要结束本次追踪，避免 ThreadLocal 泄漏到线程池复用的下一个请求
            usedTools = com.smartsupply.agent.tools.ToolSecurity.endToolTrace();
        }
        reply = enforceCitation(reply, ragContext);
        memory.append(sessionId, "user", message, com.smartsupply.common.CurrentUser.username());
        memory.append(sessionId, "assistant", reply == null ? "" : reply, com.smartsupply.common.CurrentUser.username());
        // 优先用模型 usage 回填 actual，否则用估算 estimated
        int promptTokens;
        int completionTokens;
        String tokenSource;
        long pt = MuseSparkChatModel.consumeLastPromptTokens();
        long ct = MuseSparkChatModel.consumeLastCompletionTokens();
        String src = MuseSparkChatModel.consumeLastSource();
        if (pt > 0 || ct > 0) {
            promptTokens = pt > 0 ? (int) pt : tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
            completionTokens = ct > 0 ? (int) ct : tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = src;
        } else {
            promptTokens = tokenEstimator.estimate(systemPrompt + userContent + history.size() * 200);
            completionTokens = tokenEstimator.estimate(reply == null ? "" : reply);
            tokenSource = "estimated";
        }
        double cost = tokenEstimator.estimateCostUsd(promptTokens, completionTokens);
        observation.recordChat(agentType, "java-direct", System.currentTimeMillis() - start, promptTokens, completionTokens, cost, traceId, tokenSource);
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
        return Result.ok(data);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String capturedTrace = traceId;
        long streamStart = System.currentTimeMillis();
        sseExecutor.execute(() -> {
            try {
                MDC.put(TraceIdFilter.TRACE_ID, capturedTrace == null ? "stream" : capturedTrace);
                String raw = body.getOrDefault("message", "");
                String message = guard.sanitizeUserInput(raw);
                String agentType = body.getOrDefault("agentType", "general");
                String sessionId = body.getOrDefault("sessionId", "default");
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
                                    memory.append(sessionId, "user", message, com.smartsupply.common.CurrentUser.username());
                                    memory.append(sessionId, "assistant", reply, com.smartsupply.common.CurrentUser.username());
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
                    memory.append(sessionId, "user", message, com.smartsupply.common.CurrentUser.username());
                    memory.append(sessionId, "assistant", text, com.smartsupply.common.CurrentUser.username());
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
        // 会话隔离：session 归属他人时拒绝读取（user_id 为空的存量会话兼容放行）
        String viewer = com.smartsupply.common.CurrentUser.username();
        String owner = memory.sessionOwner(sessionId);
        if (owner != null && viewer != null && !"system".equals(viewer) && !viewer.equals(owner)) {
            return Result.fail(403, "无权查看他人会话");
        }
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
}
