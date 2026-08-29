package com.smartsupply.config;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用 OpenAI 兼容路径的真实工具调用验证（默认跳过，不进 CI）。
 * 与 AiConfig.realChatModel 非 muse 分支完全同构——用于验证切到 mimo-v2.5 等支持
 * 原生 function calling 的模型后，Java 直连 agent 的 @Tool 循环端到端可用。
 * 跑法：EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=https://opencode.ai/zen/go/v1 \
 *        AI_MODEL=mimo-v2.5 mvn -B test -Dtest=OpenAiCompatibleToolCallingRealTest
 */
class OpenAiCompatibleToolCallingRealTest {

    private static final AtomicBoolean TOOL_INVOKED = new AtomicBoolean(false);

    static class InventoryStub {
        @Tool(description = "查询指定SKU的库存数量与安全库存")
        public Map<String, Object> getInventory(@ToolParam(description = "SKU编码") String skuCode) {
            TOOL_INVOKED.set(true);
            return Map.of("sku_code", skuCode, "quantity", 120, "safety_stock", 200, "belowSafety", true);
        }

        @Tool(description = "查询所有低于安全库存的SKU列表")
        public List<Map<String, Object>> listLowStock() {
            TOOL_INVOKED.set(true);
            return List.of(Map.of("sku_code", "SKU-T001-WH-M", "quantity", 120, "safety_stock", 200));
        }
    }

    private ChatClient client() {
        String key = System.getenv("OPENAI_API_KEY");
        String base = System.getenv("OPENAI_BASE_URL");
        String model = System.getenv().getOrDefault("AI_MODEL", "mimo-v2.5");
        assertNotNull(key, "OPENAI_API_KEY 未配置");
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(key).baseUrl(base);
        if (base.endsWith("/v1") || base.endsWith("/v4") || base.endsWith("/compatible-mode")) {
            apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
        }
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build())
                .build();
        return ChatClient.builder(chatModel).build();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void realModelInvokesToolAndAnswers() {
        TOOL_INVOKED.set(false);
        String reply = client().prompt()
                .user("查一下 SKU-T001-WH-M 的库存，如果低于安全库存请给出补货建议")
                .tools(new InventoryStub())
                .call().content();
        assertTrue(TOOL_INVOKED.get(), "模型未发起真实的工具调用（tool_calls）");
        assertNotNull(reply);
        assertFalse(reply.isBlank());
        System.out.println("[RealToolCall] 工具已触发, 答案片段: "
                + reply.substring(0, Math.min(120, reply.length())).replace('\n', ' '));
    }
}
