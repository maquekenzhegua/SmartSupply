package com.smartsupply.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Mock 态显式化：AI_MOCK 默认开启（离线演示是本项目特性），但必须让任何拿到服务的人
 * 一眼知道当前"模型"是关键词应答、向量是哈希伪向量——静默的 Mock 会让演示数据被误当
 * 真实模型能力。除启动横幅外，/api/agent/mode 暴露 chatMock/embeddingMock 供前端展示。
 */
@Component
public class MockModeBanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockModeBanner.class);

    private final boolean chatMock;
    private final boolean embeddingMock;

    public MockModeBanner(@Value("${smartsupply.ai.mock:true}") boolean chatMock,
                          @Value("${smartsupply.ai.embedding-mock:false}") boolean embeddingMock) {
        this.chatMock = chatMock;
        this.embeddingMock = embeddingMock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!chatMock && !embeddingMock) return;
        // embedding-mock=false 时是否真的走了真实向量由 AiConfig 依据端点可用性决定（有独立告警日志），此处不越权断言
        String embeddingLine = embeddingMock ? "MockEmbeddingModel(哈希伪向量)" : "依配置回退（未配端点时为 Mock，见 AiConfig 日志）";
        log.warn("\n============================================================\n"
                + "  ⚠ SmartSupply 运行在 Mock 模型模式（不具真实语义，勿据此评估模型能力）\n"
                + "    chat: {}      embedding: {}\n"
                + "    切换真实模型：设置 OPENAI_API_KEY(+OPENAI_BASE_URL/AI_MODEL) 且 AI_MOCK=false；\n"
                + "    切换真实向量：设置 EMBEDDING_BASE_URL/EMBEDDING_API_KEY（如本地 Ollama）且 EMBEDDING_MOCK=false\n"
                + "============================================================",
                chatMock ? "MockChatModel(关键词应答)" : "真实 ChatModel",
                embeddingLine);
    }
}
