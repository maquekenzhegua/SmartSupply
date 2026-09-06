package com.smartsupply.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private final StringRedisTemplate redis;
    // Redis 不可用时的本地兜底（单实例有效，跨实例不去重）；此前异常直接 return true 是 fail-open，
    // 等于幂等在最需要它的故障场景下静默失效。
    private final Map<String, Long> local = new ConcurrentHashMap<>();

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String key, Duration ttl) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", ttl);
            boolean acquired = Boolean.TRUE.equals(ok);
            if (acquired) local.remove(key); // 同步本地状态，避免 Redis 恢复后被本地误判重复
            return acquired;
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            local.values().removeIf(exp -> exp <= now);
            final boolean[] acquired = {false};
            local.compute(key, (k, exp) -> {
                if (exp != null && exp > now) return exp; // 仍在 TTL 内 → 判重
                acquired[0] = true;
                return now + ttl.toMillis();
            });
            log.warn("Redis 不可用，幂等降级为本地兜底（单实例有效）: {}", e.toString());
            return acquired[0];
        }
    }

    /**
     * 释放未落成结果的 key：幂等语义是"同一业务结果不重复创建"，不是"同一意图十分钟内只能尝试一次"。
     * 此前在参数校验前占 key 且失败不释放，一次校验失败就烧掉合法重试通道十分钟。
     * 注意：只在"本次尝试确定没有创建任何业务数据"时调用；创建成功后不得释放。
     */
    public void release(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.debug("redis release failed, fallback local: {}", e.toString());
        }
        local.remove(key);
    }
}
