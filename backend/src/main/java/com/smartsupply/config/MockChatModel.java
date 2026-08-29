package com.smartsupply.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import reactor.core.publisher.Flux;

/**
 * 无 Key 时的 Mock 模型：保证离线演示 + Token 估算可观测，同样回填 usage 到 metadata 供 ObservationService 对账。
 */
public class MockChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MockChatModel.class);

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.currentTimeMillis();
        String last = prompt.getInstructions().isEmpty() ? ""
                : prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
        if (last == null) last = "";

        String lower = last.toLowerCase();
        String text;
        if (lower.contains("合同") || lower.contains("风控") || lower.contains("风险")) {
            text = """
                    [Mock] 合同风控分析完成：
                    1) 抽取要素：甲方/乙方/金额/账期/违约金/交付时间已结构化
                    2) RAG 召回 2 条历史风控规范：禁止无限连带责任、违约金不超30%
                    3) 风险：检测到“乙方承担一切连带责任”属高风险，建议改为“在乙方过错范围内承担有限责任”
                    4) 已生成《AI风控报告》可写入 contract_risk_report 表
                    提示：配置 OPENAI_API_KEY / DASHSCOPE_API_KEY 后将调用真实大模型复核。
                    """;
        } else if (lower.contains("库存") || lower.contains("补货") || lower.contains("采购")) {
            text = """
                    [Mock] 补货预测完成：
                    - SKU-T001-WH-M 当前库存120，安全库存200，预计7天后断货
                    - 建议：向 深圳创优服装厂 采购500件，预估毛利最高，是否直接创建采购单？
                    提示：真实模型将结合销量/季节/物流时效做预测并调用 createPurchaseOrder 工具。
                    """;
        } else if (lower.contains("退货") || lower.contains("分析") || lower.contains("sql") || lower.contains("华南")) {
            text = """
                    [Mock] 经营分析完成：
                    SELECT category, COUNT(*)/SUM(COUNT(*)) OVER() AS return_rate FROM orders GROUP BY category;
                    结论（Mock）：华南区“服装”品类退货率最高(12.3%)，主因尺码偏大。已生成 ECharts 配置。
                    提示：真实 NL2SQL 会经 SqlValidator 校验后只读执行。
                    """;
        } else {
            text = "[Mock] 已收到：" + last + "\n这是 Mock 模型的演示回复。配置真实 Key 后将由 DeepSeek/通义千问/OpenAI 生成带 Tool Calling 的回答。";
        }

        AssistantMessage msg = new AssistantMessage(text);
        int pt = estimateTokens(prompt.getInstructions().stream().map(m -> m.getText() == null ? "" : m.getText()).reduce("", (a, b) -> a + b));
        int ct = estimateTokens(text);
        msg.getMetadata().put("promptTokens", pt);
        msg.getMetadata().put("completionTokens", ct);
        msg.getMetadata().put("tokenSource", "estimated");
        log.info("mock chat promptTokens~{} completionTokens~{} latencyMs={} source=estimated", pt, ct, System.currentTimeMillis() - start);
        Generation gen = new Generation(msg);
        return new ChatResponse(java.util.List.of(gen));
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 3);
    }

    /**
     * Mock 的流式：内容仍是离线演示文案，但走真实 Flux 分片推送，
     * 保证 SSE 管道（ChatClient.stream -> MessageAggregator -> SseEmitter）无需真实 Key 也能端到端验证。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse full = call(prompt);
        String text = full.getResults().isEmpty() || full.getResults().get(0).getOutput() == null
                ? "" : full.getResults().get(0).getOutput().getText();
        if (text == null) text = "";
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 6) {
            chunks.add(text.substring(i, Math.min(text.length(), i + 6)));
        }
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(12))
                .map(c -> new ChatResponse(List.of(new Generation(new AssistantMessage(c)))));
    }

    @Override
    public ChatOptions getDefaultOptions() { return null; }
}
