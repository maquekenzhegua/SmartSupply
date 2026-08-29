package com.smartsupply.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 补货预测 Agent 的自主规划形态：定时扫描低库存，预留扩展为 LLM 决策。
 * 面试可讲：这是典型的“自主 Agent”，由调度器驱动，无需用户唤醒。
 */
@Component
public class ReplenishmentScheduler {

    private final JdbcTemplate jdbc;

    public ReplenishmentScheduler(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 0 2 * * *") // 每天 02:00
    public void scanLowStock() {
        List<Map<String, Object>> lows = jdbc.queryForList("""
                SELECT s.sku_code, i.quantity, i.safety_stock
                FROM inventory i JOIN sku s ON s.id=i.sku_id
                WHERE i.quantity < i.safety_stock
                """);
        if (!lows.isEmpty()) {
            System.out.println("[ReplenishmentAgent] 发现 " + lows.size() + " 个 SKU 低于安全库存，建议触发补货流程: " + lows);
            // 真实：此处调用 ChatClient + Tools 生成采购建议并写入待办
        }
    }
}
