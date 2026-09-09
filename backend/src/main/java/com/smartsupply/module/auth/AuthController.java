package com.smartsupply.module.auth;

import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public AuthController(JwtService jwtService, PasswordEncoder encoder, JdbcTemplate jdbc,
                          org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.jwtService = jwtService;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.redis = redis;
    }

    // 登录爆破防线：此前 /api/auth/** permitAll 且无限流，可无限尝试密码；
    // 10 次/分钟/IP（Redis Lua 计数，多实例一致），配合防用户名枚举的同文案设计
    @RateLimit(permitsPerMinute = 10, key = "login")
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            return Result.fail(400, "用户名与密码不能为空");
        }
        if (username.length() > 64) return Result.fail(400, "用户名或密码错误");
        Map<String, Object> row;
        try {
            // 带引号的小写别名：H2(PG模式) 会把未加引号标识符转大写，queryForMap 的键会变 PASSWORD_HASH
            row = jdbc.queryForMap("SELECT password_hash AS \"password_hash\", role AS \"role\" FROM sys_user WHERE username=?", username);
        } catch (EmptyResultDataAccessException e) {
            // 与密码错误同一文案 + 同一响应码，避免用户名枚举
            return Result.fail(401, "用户名或密码错误");
        }
        String hash = String.valueOf(row.get("password_hash"));
        // BCrypt 慢哈希比较；明文/占位哈希（历史脏数据）不匹配任何输入，登录必然失败
        if (!encoder.matches(password, hash)) {
            return Result.fail(401, "用户名或密码错误");
        }
        String role = String.valueOf(row.getOrDefault("role", "USER"));
        String token = jwtService.generate(username, role);
        return Result.ok(Map.of("token", token, "username", username, "role", role));
    }

    /**
     * 注销：把当前 token 的 jti 写入 Redis 黑名单（TTL=剩余有效期，到期自动出列），
     * JwtAuthFilter 每次请求校验——12h 短周期 + 可撤销，不引入 refresh token 控制改动面。
     * Redis 不可用时如实降级：注销仍返回成功，但吊销只到 token 自然过期为止（fail-open，
     * 与限流器同一可用性优先口径）。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(auth.substring(7));
                String jti = claims.getId();
                long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
                if (jti != null && ttlMs > 0) {
                    redis.opsForValue().set("jwt:revoke:" + jti, "1",
                            java.time.Duration.ofMillis(ttlMs));
                }
            } catch (Exception e) {
                // token 本身已无效（过期/伪造）：无需吊销，按成功返回避免信息泄漏
                log.debug("logout token parse failed: {}", e.toString());
            }
        }
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<Map<String, String>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Result.fail(401, "未登录");
        }
        try {
            Claims claims = jwtService.parse(auth.substring(7));
            String role = claims.get("role", String.class);
            return Result.ok(Map.of(
                    "username", String.valueOf(claims.getSubject()),
                    "role", role == null ? "USER" : role));
        } catch (Exception e) {
            log.debug("/me token parse failed: {}", e.toString());
            return Result.fail(401, "登录已过期，请重新登录");
        }
    }
}
