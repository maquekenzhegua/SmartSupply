package com.smartsupply.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型装配：
 * - smartsupply.ai.mock=true（默认）：Chat/Embedding 全部走 Mock，保证离线演示与 CI 不依赖外部服务。
 * - mock=false：muse 网关走 MuseSparkChatModel(/responses)；其余任何 OpenAI 兼容端点
 *   (OpenAI / DeepSeek / 智谱 / DashScope compatible-mode / vLLM) 走标准 OpenAiChatModel，
 *   该路径具备原生 Tool Calling 与流式能力。
 * - Embedding：与 Chat 解耦，独立配置 smartsupply.ai.embedding.base-url/api-key（回退 spring.ai.openai.*）。
 *   典型用法：本地 Ollama（OpenAI 兼容 /v1/embeddings，如 qwen3-embedding:0.6b，1024 维）——免费、离线、中文友好；
 *   也可指向 OpenAI text-embedding-3-small（1536 维，需同步 VECTOR_DIMENSIONS=1536 与建表列宽）。
 *   muse/opencode 网关无 /embeddings，不可用；不可用时回退 Mock（哈希伪向量，检索不具备语义）。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "smartsupply.ai.mock", havingValue = "false")
    ChatModel realChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("smartsupply.ai.mock=false 但未配置 api-key，降级为 MockChatModel");
            return new MockChatModel();
        }
        boolean muse = baseUrl != null && baseUrl.contains("opencode") && model != null && model.toLowerCase().contains("muse");
        if (muse) {
            log.info("ChatModel=muse-spark(/responses) baseUrl={} model={}", baseUrl, model);
            return new MuseSparkChatModel(apiKey, baseUrl, model);
        }
        // base-url 已带版本段(/v1、/v4、/compatible-mode)时，兼容层需去掉默认的 /v1 前缀
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl);
        if (baseUrl != null && (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v4") || baseUrl.endsWith("/compatible-mode"))) {
            apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
        }
        log.info("ChatModel=openai-compatible baseUrl={} model={}", baseUrl, model);
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(temperature).build())
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "smartsupply.ai.mock", havingValue = "true", matchIfMissing = true)
    ChatModel mockChatModel() {
        return new MockChatModel();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "smartsupply.ai.embedding-mock", havingValue = "true")
    EmbeddingModel mockEmbeddingModelForced(
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int dims) {
        log.warn("Embedding=Mock(强制, smartsupply.ai.embedding-mock=true)：向量检索不具备语义");
        return new MockEmbeddingModel(dims);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "smartsupply.ai.embedding-mock", havingValue = "false", matchIfMissing = true)
    EmbeddingModel embeddingModel(
            @Value("${smartsupply.ai.embedding.api-key:${spring.ai.openai.api-key:}}") String embedApiKey,
            @Value("${smartsupply.ai.embedding.base-url:${spring.ai.openai.base-url:https://api.openai.com}}") String embedBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String embeddingModel,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int dims) {
        // 与 Chat 解耦：可单独指向 Ollama(OpenAI 兼容 /v1/embeddings) 等本地服务；
        // muse/opencode 网关不提供 /embeddings，不可用作 embedding 端点
        boolean usable = embedApiKey != null && !embedApiKey.isBlank()
                && embedBaseUrl != null && !embedBaseUrl.isBlank() && !embedBaseUrl.contains("opencode");
        if (!usable) {
            log.warn("Embedding=Mock（未配置 embedding 端点或端点为 muse 网关）：向量检索不具备语义");
            return new MockEmbeddingModel(dims);
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(embedApiKey).baseUrl(embedBaseUrl);
        if (embedBaseUrl.endsWith("/v1") || embedBaseUrl.endsWith("/v4") || embedBaseUrl.endsWith("/compatible-mode")) {
            apiBuilder.embeddingsPath("/embeddings");
        }
        log.info("Embedding=openai-compatible baseUrl={} model={} 维度须与 VECTOR_DIMENSIONS/pgvector 列一致", embedBaseUrl, embeddingModel);
        return new OpenAiEmbeddingModel(
                apiBuilder.build(),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(embeddingModel).build());
    }
}
