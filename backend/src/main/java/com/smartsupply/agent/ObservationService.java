package com.smartsupply.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
public class ObservationService {

    private static final Logger log = LoggerFactory.getLogger(ObservationService.class);
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final ExecutorService persistPool;

    public ObservationService(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.persistPool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000), r -> {
                    Thread t = new Thread(r, "obs-persist");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void recordChat(String agentType, String mode, long latencyMs, int promptTokens, int completionTokens, double costUsd, String traceId) {
        recordChat(agentType, mode, latencyMs, promptTokens, completionTokens, costUsd, traceId, "estimated");
    }

    public void recordChat(String agentType, String mode, long latencyMs, int promptTokens, int completionTokens, double costUsd, String traceId, String source) {
        log.info("agent.chat traceId={} agentType={} mode={} source={} latencyMs={} promptTokens={} completionTokens={} totalTokens={} costUsd~{}",
                traceId, agentType, mode, source, latencyMs, promptTokens, completionTokens, promptTokens + completionTokens, String.format("%.4f", costUsd));
        Timer.builder("agent.chat.latency").tag("agentType", agentType).tag("mode", mode)
                .register(registry).record(latencyMs, TimeUnit.MILLISECONDS);
        Counter.builder("agent.chat.tokens").tag("agentType", agentType).tag("type", "prompt").tag("source", source)
                .register(registry).increment(promptTokens);
        Counter.builder("agent.chat.tokens").tag("agentType", agentType).tag("type", "completion").tag("source", source)
                .register(registry).increment(completionTokens);
        Counter.builder("agent.chat.count").tag("agentType", agentType).tag("mode", mode).tag("source", source)
                .register(registry).increment();
        try {
            Counter.builder("agent.chat.cost_usd").tag("agentType", agentType).tag("mode", mode).tag("source", source)
                    .register(registry).increment(costUsd);
        } catch (Exception ignored) {}
    }

    public void recordChat(String agentType, String mode, long latencyMs, int promptTokens, int completionTokens, String traceId) {
        recordChat(agentType, mode, latencyMs, promptTokens, completionTokens, 0.0, traceId, "estimated");
    }

    public void recordTtfb(String agentType, String mode, long ttfbMs) {
        Timer.builder("agent.chat.ttft").tag("agentType", agentType).tag("mode", mode)
                .register(registry).record(ttfbMs, TimeUnit.MILLISECONDS);
        log.info("agent.ttft agentType={} mode={} ttfbMs={}", agentType, mode, ttfbMs);
    }

    public void recordTool(String toolName, boolean success, long latencyMs) {
        Counter.builder("agent.tool.count").tag("tool", toolName).tag("success", String.valueOf(success))
                .register(registry).increment();
        Timer.builder("agent.tool.latency").tag("tool", toolName)
                .register(registry).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordRag(long latencyMs, int hits) {
        Timer.builder("rag.recall.latency").tag("stage", "service")
                .register(registry).record(latencyMs, TimeUnit.MILLISECONDS);
        Counter.builder("rag.recall.hits").register(registry).increment(hits);
    }

    public MeterRegistry getRegistry() { return registry; }

    // ---- persistent observability (async, never block request) ----

    public long insertRun(String traceId, String username, Long userId, String sessionId, String agentType, String mode, String model) {
        try {
            String sql = "INSERT INTO agent_run(trace_id, user_id, username, session_id, agent_type, mode, status, model) VALUES (?,?,?,?,?,?,?,?)";
            // 直接取生成主键：此前"INSERT 后 SELECT MAX(id) WHERE trace_id=?"在并发同 trace 下会拿错行
            org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, traceId);
                if (userId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, userId);
                ps.setString(3, username);
                ps.setString(4, sessionId);
                ps.setString(5, agentType);
                ps.setString(6, mode);
                ps.setString(7, "RUNNING");
                ps.setString(8, model);
                return ps;
            }, kh);
            Number id = kh.getKey();
            return id == null ? -1 : id.longValue();
        } catch (Exception e) {
            log.warn("insertRun failed: {}", e.toString());
            return -1;
        }
    }

    public void updateRunMode(long runId, String mode) {
        persistPool.execute(() -> {
            try {
                jdbc.update("UPDATE agent_run SET mode=? WHERE id=?", mode, runId);
            } catch (Exception e) { log.warn("updateRunMode failed runId={}: {}", runId, e.toString()); }
        });
    }

    public void completeRun(long runId, String status, long latencyMs, Long ttfbMs, int promptTokens, int completionTokens, double costUsd, String tokenSource, String errorMsg) {
        int total = promptTokens + completionTokens;
        persistPool.execute(() -> {
            try {
                jdbc.update("UPDATE agent_run SET status=?, latency_ms=?, ttfb_ms=?, prompt_tokens=?, completion_tokens=?, total_tokens=?, cost_usd=?, token_source=?, error_msg=? WHERE id=?",
                        status, (int) latencyMs, ttfbMs == null ? null : ttfbMs.intValue(), promptTokens, completionTokens, total, costUsd, tokenSource, errorMsg, runId);
            } catch (Exception e) { log.warn("completeRun failed runId={}: {}", runId, e.toString()); }
        });
    }

    public void insertStep(long runId, int seq, String node, String name, String inputDigest, String outputDigest, long latencyMs, boolean success) {
        persistPool.execute(() -> {
            try {
                jdbc.update("INSERT INTO agent_step(run_id, seq, node, name, input_digest, output_digest, latency_ms, success) VALUES (?,?,?,?,?,?,?,?)",
                        runId, seq, node, name, inputDigest, outputDigest, (int) latencyMs, success);
            } catch (Exception e) { log.warn("insertStep failed: {}", e.toString()); }
        });
    }

    public void insertToolCall(long runId, Long stepId, String tool, String argsJson, String resultDigest, boolean success, long latencyMs, String userRole) {
        persistPool.execute(() -> {
            try {
                jdbc.update("INSERT INTO agent_tool_call(run_id, step_id, tool, args_json, result_digest, success, latency_ms, user_role) VALUES (?,?,?,?,?,?,?,?)",
                        runId, stepId, tool, argsJson, resultDigest, success, (int) latencyMs, userRole);
            } catch (Exception e) { log.warn("insertToolCall failed: {}", e.toString()); }
        });
    }
}
