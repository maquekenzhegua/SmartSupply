package com.smartsupply.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 补货预测 Agent 的自主规划形态：定时扫描低库存 -> LLM 生成补货建议 -> 落库 replenishment_suggestion。
 * LLM 失败时降级为规则建议（缺口 = 安全库存 - 现有库存），保证调度器任何情况下都有产出可审计。
 * 面试可讲：这是典型的“自主 Agent”，由调度器驱动、无需用户唤醒，且写操作只产生建议（不直接下单），仍是 HITL 语义。
 */
@Component
public class ReplenishmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentScheduler.class);

    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final boolean enabled;

    public ReplenishmentScheduler(JdbcTemplate jdbc, ChatClient chatClient,
                                  @Value("${smartsupply.agent.replenishment-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.chatClient = chatClient;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${smartsupply.agent.replenishment-cron:0 0 2 * * *}")
    public void scanLowStock() {
        if (!enabled) return;
        List<Map<String, Object>> lows = jdbc.queryForList("""
                SELECT s.sku_code, i.quantity, i.safety_stock
                FROM inventory i JOIN sku s ON s.id=i.sku_id
                WHERE i.quantity < i.safety_stock
                """);
        if (lows.isEmpty()) {
            log.info("[ReplenishmentAgent] 扫描完成：无低于安全库存的 SKU");
            return;
        }
        for (Map<String, Object> row : lows) {
            String skuCode = String.valueOf(row.get("sku_code"));
            int qty = ((Number) row.get("quantity")).intValue();
            int safe = ((Number) row.get("safety_stock")).intValue();
            String suggestion;
            String source;
            try {
                suggestion = chatClient.prompt().user("""
                        你是补货决策助手。SKU=%s 当前库存=%d 安全库存=%d。
                        只输出一行补货建议，包含：建议采购数量（不超过缺口的3倍且不超过5000）与一句话理由。
                        """.formatted(skuCode, qty, safe)).call().content();
                if (suggestion == null || suggestion.isBlank()) throw new IllegalStateException("LLM 空回复");
                source = "llm";
            } catch (Exception e) {
                // 降级：规则建议，调度器任何情况下都有产出
                log.warn("[ReplenishmentAgent] LLM 建议失败，降级规则: {}", e.toString());
                suggestion = "规则建议：采购 " + Math.min((safe - qty) * 2, 5000) + " 件（缺口 " + (safe - qty) + " 的2倍，上限5000）";
                source = "rule";
            }
            try {
                jdbc.update("""
                        INSERT INTO replenishment_suggestion(sku_code, quantity, safety_stock, suggestion, source)
                        VALUES (?,?,?,?,?)
                        """, skuCode, qty, safe, suggestion, source);
            } catch (Exception e) {
                log.warn("[ReplenishmentAgent] 建议落库失败 sku={}: {}", skuCode, e.toString());
            }
            log.info("[ReplenishmentAgent] sku={} 库存={}/安全={} 建议[{}]: {}", skuCode, qty, safe, source, suggestion);
        }
    }
}
