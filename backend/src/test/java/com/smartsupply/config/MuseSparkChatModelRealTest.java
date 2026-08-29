package com.smartsupply.config;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 LLM 验证（默认跳过，不进 CI）：
 * 跑法：EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=https://opencode.ai/zen/go/v1 \
 *        AI_MODEL=muse-spark-1.2-contributor mvn -B test -Dtest=MuseSparkChatModelRealTest
 * 验证 /responses 协议的同步 call 与真流式 stream 在真实网关上端到端可用。
 */
class MuseSparkChatModelRealTest {

    private static final String CONTRACT_QUESTION = """
            公司合同风控规范：1. 禁止无限连带责任条款；2. 违约金不得超过合同额30%。
            问题：合同里写了乙方承担无限连带责任且违约金50%，有什么风险？简要回答。
            """;

    private MuseSparkChatModel model() {
        String key = System.getenv("OPENAI_API_KEY");
        String base = System.getenv("OPENAI_BASE_URL");
        String model = System.getenv().getOrDefault("AI_MODEL", "muse-spark-1.2-contributor");
        assertNotNull(key, "OPENAI_API_KEY 未配置");
        assertNotNull(base, "OPENAI_BASE_URL 未配置");
        return new MuseSparkChatModel(key, base, model);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void realCallReturnsAnswerWithUsage() {
        ChatResponse resp = model().call(new Prompt(CONTRACT_QUESTION));
        String text = resp.getResults().isEmpty() || resp.getResults().get(0).getOutput() == null
                ? null : resp.getResults().get(0).getOutput().getText();
        assertNotNull(text);
        assertFalse(text.isBlank(), "真实模型返回空文本");
        System.out.println("[RealLLM] call 答案长度=" + text.length() + " 内容片段=" + text.substring(0, Math.min(80, text.length())).replace('\n', ' '));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void realStreamEmitsMultipleDeltas() {
        Flux<ChatResponse> flux = model().stream(new Prompt(CONTRACT_QUESTION));
        List<ChatResponse> chunks = flux.collectList().block(Duration.ofSeconds(180));
        assertNotNull(chunks, "流式未返回任何 chunk");
        assertTrue(chunks.size() > 3, "流式 chunk 数量异常少: " + chunks.size());
        StringBuilder sb = new StringBuilder();
        chunks.forEach(c -> {
            if (!c.getResults().isEmpty() && c.getResults().get(0).getOutput() != null) {
                sb.append(c.getResults().get(0).getOutput().getText());
            }
        });
        assertFalse(sb.toString().isBlank(), "流式聚合文本为空");
        System.out.println("[RealLLM] stream chunks=" + chunks.size() + " 总长度=" + sb.length());
    }
}
