package com.smartsupply.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /**
     * INCR + PEXPIRE 原子化：此前的"先 increment 再 expire"两步在两次调用之间宕机会留下
     * 永不过期的计数键，把用户永久锁死在 429。Lua 脚本在 Redis 单线程内一次执行完成。
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
            "return c", Long.class);

    private final StringRedisTemplate redis;
    // 无 Redis 时本拦截器"异常放行"（可用性优先），但必须留痕而非静默；
    // 测试 profile 显式关闭，保证任何环境下的确定性（见 src/test/resources/application-test.yml）。
    private final boolean enabled;
    // 仅当部署在可信反代之后才允许从 X-Forwarded-For 取客户端 IP，否则该头可被任意伪造刷掉限流配额
    private final boolean trustProxy;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                @Value("${smartsupply.ratelimit.enabled:true}") boolean enabled,
                                @Value("${smartsupply.ratelimit.trust-proxy:false}") boolean trustProxy) {
        this.redis = redis;
        this.enabled = enabled;
        this.trustProxy = trustProxy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) return true;
        if (!(handler instanceof HandlerMethod hm)) return true;
        RateLimit ann = hm.getMethodAnnotation(RateLimit.class);
        if (ann == null) ann = hm.getBeanType().getAnnotation(RateLimit.class);
        if (ann == null) return true;
        // key 维度 = 接口 + 客户端 IP + 登录用户：仅 IP 会让 NAT 后多用户共享配额互相锁死、
        // 单用户换 IP 绕过配额；匿名请求退化为仅 IP 维度
        String username = com.smartsupply.common.CurrentUser.username();
        String key = "rl:" + (ann.key().isBlank() ? hm.getMethod().getName() : ann.key()) + ":" + clientIp(request)
                + (username == null || username.isBlank() ? "" : ":" + username);
        try {
            Long count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(Duration.ofMinutes(1).toMillis()));
            if (count != null && count > ann.permitsPerMinute()) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":429,\"msg\":\"请求过于频繁，请稍后再试\",\"data\":null}");
                return false;
            }
        } catch (Exception e) {
            log.warn("rate limit backend unavailable, failing open (key={}): {}", key, e.toString());
        }
        return true;
    }

    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
