package com.smartsupply.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 观测：链路与 Token/成本统计，Prometheus 可抓取，日志可回溯。
 * 成本分 estimated(离线Mock) / actual(模型 usage 回填)，TTFT 单独计时，面试可指着 /actuator/prometheus 现场对账。
 */
@Service
public class ObservationService {

    private static final Logger log = LoggerFactory.getLogger(ObservationService.class);
    private final MeterRegistry registry;

    public ObservationService(MeterRegistry registry) { this.registry = registry; }

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
}
