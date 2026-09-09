package com.smartsupply.agent.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 用户级日成本预算（成本管控的"闸"，区别于 agent_run 的"表"）：
 * 每次对话前 check()，超限直接拒绝（429），不降级不伪装——花不起就是花不起；
 * 每次运行完成 addCost() 累加真实/估算成本。
 *
 * 口径（双源取大）：Redis 计数器（agent:budget:{day}:{username}，TTL 48h）含进行中的运行
 * 但可能丢失（重启/淘汰/降级），DB 台账（agent_run.cost_usd 当日 SUM）只含已完成运行但可信。
 * 任一源显示超限都必须拦截——两源不一致时宁可误拦不可漏拦（实跑教训：PG 上 insertRun 曾
 * 返回 -1 导致 addCost 整条链路不执行，Redis 单源口径会静默放走全部超限）。
 * limit=0（默认）表示预算管控关闭——治理能力默认显式开启，而不是悄悄改变现网行为。
 */
@Service
public class AgentBudgetService {

    private static final Logger log = LoggerFactory.getLogger(AgentBudgetService.class);

    public record BudgetStatus(double limitUsd, double usedUsd, boolean over) {
        public String describe() {
            return String.format("$%.4f / $%.2f", usedUsd, limitUsd);
        }
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final double limitUsd;

    public AgentBudgetService(StringRedisTemplate redis, JdbcTemplate jdbc,
                              @Value("${smartsupply.agent.daily-cost-limit-usd:0}") double limitUsd) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.limitUsd = Math.max(0, limitUsd);
    }

    public boolean enabled() { return limitUsd > 0; }

    public BudgetStatus check(String username) {
        double used = usedUsd(username);
        return new BudgetStatus(limitUsd, used, enabled() && used >= limitUsd);
    }

    public double usedUsd(String username) {
        if (!enabled() || username == null || username.isBlank()) return 0;
        double redisUsed = 0;
        try {
            String v = redis.opsForValue().get(key(username));
            if (v != null) redisUsed = Double.parseDouble(v);
        } catch (Exception e) {
            log.debug("Redis 预算计数不可用: {}", e.toString());
        }
        double dbUsed = dbUsedToday(username);
        // 双源取大：Redis 含进行中运行（可能丢），DB 只含已完成运行（可信但滞后）
        return Math.max(redisUsed, dbUsed);
    }

    public void addCost(String username, double costUsd) {
        if (!enabled() || username == null || username.isBlank() || costUsd <= 0) return;
        String k = key(username);
        try {
            Double n = redis.opsForValue().increment(k, costUsd);
            if (n != null) redis.expire(k, 48, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("Redis 预算累加失败（DB 台账仍是事实源）: {}", e.toString());
        }
    }

    private String key(String username) {
        return "agent:budget:" + LocalDate.now(ZoneId.systemDefault()) + ":" + username;
    }

    private double dbUsedToday(String username) {
        try {
            // Timestamp 参数直传（避免 ?::timestamptz 方言分裂；JDBC 驱动对 H2/PG 都做类型适配）
            java.sql.Timestamp dayStart = java.sql.Timestamp.valueOf(
                    LocalDate.now(ZoneId.systemDefault()).atStartOfDay());
            Double sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(cost_usd),0) FROM agent_run WHERE username=? AND created_at >= ?",
                    Double.class, username, dayStart);
            return sum == null ? 0 : sum;
        } catch (Exception e) {
            log.warn("DB 预算回退查询失败（按 0 处理，宁可漏拦不可误拦）: {}", e.toString());
            return 0;
        }
    }
}
