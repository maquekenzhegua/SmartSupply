package com.smartsupply.module.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final String rawSecret;
    private final long expireMs;
    private SecretKey key;

    public JwtService(@Value("${smartsupply.jwt.secret}") String secret,
                      @Value("${smartsupply.jwt.expire-hours:12}") long expireHours) {
        this.rawSecret = secret;
        this.expireMs = expireHours * 3600_000L;
    }

    @PostConstruct
    void init() {
        if (rawSecret == null || rawSecret.length() < 32) {
            throw new IllegalStateException("smartsupply.jwt.secret 长度必须 >=32 字符，prod 请通过环境变量 JWT_SECRET 注入");
        }
        boolean isDefaultDevSecret = rawSecret.contains("smartsupply-dev-secret");
        String active = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE",
                System.getProperty("spring.profiles.active", ""));
        if ("prod".equals(active) && isDefaultDevSecret) {
            throw new IllegalStateException("prod 环境禁止使用默认 JWT secret，请设置 JWT_SECRET 环境变量");
        }
        if (isDefaultDevSecret) {
            log.warn("Using default dev JWT secret — prod must override via JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(rawSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMs))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
