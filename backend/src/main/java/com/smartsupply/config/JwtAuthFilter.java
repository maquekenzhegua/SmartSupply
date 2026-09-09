package com.smartsupply.config;

import com.smartsupply.module.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public JwtAuthFilter(JwtService jwtService, org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.jwtService = jwtService;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                var claims = jwtService.parse(token);
                // 吊销检查（jti 黑名单）：注销/改密后 token 立即失效。Redis 不可用时 fail-open
                // （可用性优先，与限流器同口径），此时吊销退化为"等 token 自然过期"
                String jti = claims.getId();
                if (jti != null && jti.isBlank()) jti = null;
                if (jti != null) {
                    try {
                        if (Boolean.TRUE.equals(redis.hasKey("jwt:revoke:" + jti))) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"msg\":\"登录状态已注销，请重新登录\",\"data\":null}");
                            return;
                        }
                    } catch (Exception redisDown) {
                        // fail-open：继续按有效 token 处理
                    }
                }
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // 携带了 token 但解析失败（过期/篡改/格式错误）：显式 401 而非静默降级为匿名。
                // 此前吞掉异常导致过期 token 得到匿名 403"无权限"，前端 401→跳登录 的链路永不触发。
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"登录已过期，请重新登录\",\"data\":null}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
