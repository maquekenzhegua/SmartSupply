package com.smartsupply.agent.cost;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户级日预算（成本管控的闸）：Redis 计数口径、DB 回退口径、关闭态、累加。
 */
class AgentBudgetServiceTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void overLimitWhenRedisCounterExceeds() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("6.0");
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 5.0);
        AgentBudgetService.BudgetStatus st = s.check("alice");
        assertTrue(st.over());
        assertEquals(6.0, st.usedUsd(), 1e-9);
        assertTrue(st.describe().contains("$6.0000"));
    }

    @Test
    void underLimitPasses() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("1.0");
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 5.0);
        assertFalse(s.check("alice").over());
    }

    @Test
    void disabledByDefaultNeverBlocks() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("9999");
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 0);
        AgentBudgetService.BudgetStatus st = s.check("alice");
        assertFalse(st.over());
        assertEquals(0, st.usedUsd(), 1e-9);  // 关闭态不做任何口径查询
    }

    @Test
    void redisDownFallsBackToDbLedger() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(2.5);
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 2.0);
        AgentBudgetService.BudgetStatus st = s.check("bob");
        assertTrue(st.over());
        assertEquals(2.5, st.usedUsd(), 1e-9);
    }

    @Test
    void dbLedgerOverBudgetBlocksEvenWhenRedisUnder() {
        // 实跑教训（insertRun PG 方言 bug 曾让 addCost 整链不执行、Redis 计数恒为 0）：
        // Redis 只有小额计数、DB 台账已超限 → 双源取大必须拦截，不得因 Redis 单源口径静默放行
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("0.1");
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(9.9);
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 1.0);
        AgentBudgetService.BudgetStatus st = s.check("admin");
        assertTrue(st.over());
        assertEquals(9.9, st.usedUsd(), 1e-9);
    }

    @Test
    void dbFallbackFailureTreatsAsZero() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class)))
                .thenThrow(new RuntimeException("table missing"));
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 2.0);
        // 宁可漏拦不可误拦：口径故障按 0 处理，不把用户锁死在门外
        assertFalse(s.check("bob").over());
    }

    @Test
    void addCostIncrementsRedisWithTtl() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString(), anyDouble())).thenReturn(1.5);
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 5.0);
        s.addCost("alice", 1.5);
        verify(ops).increment(anyString(), eq(1.5));
        verify(redis).expire(anyString(), eq(48L), any(TimeUnit.class));
    }

    @Test
    void addCostIgnoredWhenDisabledOrNonPositive() {
        AgentBudgetService s = new AgentBudgetService(redis, jdbc, 0);
        s.addCost("alice", 1.5);
        s.addCost("alice", 0);
        s.addCost("alice", -1);
        verifyNoInteractions(ops);
    }
}
