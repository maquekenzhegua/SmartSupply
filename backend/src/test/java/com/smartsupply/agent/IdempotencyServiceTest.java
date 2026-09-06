package com.smartsupply.agent;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 幂等服务回归：Redis 故障时不得 fail-open（此前异常直接 return true，
 * 重复提交在故障场景下静默放行），须由本地兜底继续判重。
 */
class IdempotencyServiceTest {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        return redis;
    }

    @Test
    void redisDownFallsBackToLocalDedupInsteadOfFailOpen() {
        IdempotencyService svc = new IdempotencyService(redisDown());
        Duration ttl = Duration.ofMinutes(10);
        assertTrue(svc.tryAcquire("idem:po:admin:1:SKU-A:100:20", ttl), "首次获取应成功");
        assertFalse(svc.tryAcquire("idem:po:admin:1:SKU-A:100:20", ttl),
                "Redis 故障时相同 key 在 TTL 内必须判重，不得 fail-open");
    }

    @Test
    void redisDownDifferentKeysRemainIndependent() {
        IdempotencyService svc = new IdempotencyService(redisDown());
        Duration ttl = Duration.ofMinutes(10);
        assertTrue(svc.tryAcquire("idem:po:admin:1:SKU-A:100:20", ttl));
        assertTrue(svc.tryAcquire("idem:po:admin:1:SKU-B:100:20", ttl), "不同幂等键互不影响");
    }

    @Test
    void expiredLocalEntryIsAcquirableAgain() {
        IdempotencyService svc = new IdempotencyService(redisDown());
        assertTrue(svc.tryAcquire("k", Duration.ofMillis(1)));
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        assertTrue(svc.tryAcquire("k", Duration.ofMinutes(10)), "本地兜底过期后应可再次获取");
    }
}
