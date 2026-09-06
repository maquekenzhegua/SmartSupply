package com.smartsupply.module.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时保证演示账号可用且口令为真实 BCrypt 哈希。
 *
 * 背景：V1 迁移为保持幂等植入的是占位哈希（DUMMY_BCRYPT_HASH_REPLACE_ON_STARTUP），
 * 无法通过 BCrypt matches。本 runner 负责：
 *  1) admin 不存在则创建；存在但哈希为占位值、或显式设置了 DEMO_ADMIN_PASSWORD 时重置为真实哈希；
 *  2) 额外提供低权限账号 ops（DEMO_OPS_PASSWORD，默认 ops123，角色 OPS），
 *     使"写工具要求 ADMIN"的 RBAC 拒绝路径可以真实演示而非只有单账号；
 *  3) prod 环境使用默认口令时大声告警（不阻断启动，由 compose/部署侧强制注入环境变量）。
 */
@Component
public class DemoUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);
    private static final String DUMMY_HASH_MARK = "REPLACE_ON_STARTUP";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${smartsupply.demo.admin-password:admin123}")
    private String adminPassword;
    @Value("${smartsupply.demo.ops-password:ops123}")
    private String opsPassword;

    public DemoUserInitializer(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String envAdmin = System.getenv("DEMO_ADMIN_PASSWORD");
        String envOps = System.getenv("DEMO_OPS_PASSWORD");
        boolean prod = "prod".equals(System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE",
                System.getProperty("spring.profiles.active", "")));
        try {
            upsert("admin", envAdmin != null && !envAdmin.isBlank() ? envAdmin : adminPassword,
                    "管理员", "ADMIN", envAdmin != null);
            upsert("ops", envOps != null && !envOps.isBlank() ? envOps : opsPassword,
                    "运营专员", "OPS", envOps != null);
            if (prod) {
                log.warn("PROD 环境正在使用演示账号体系：请通过 DEMO_ADMIN_PASSWORD/DEMO_OPS_PASSWORD 注入强口令，" +
                        "或接入正式的用户管理后再对外暴露");
            }
        } catch (Exception e) {
            // 表未就绪（如 H2 演示库初始化失败）时只告警，不阻断启动
            log.warn("演示账号初始化失败（登录将不可用）: {}", e.toString());
        }
    }

    private void upsert(String username, String rawPassword, String nickname, String role, boolean explicit) {
        // 带引号的小写别名：H2(PG模式) 未加引号标识符转大写，取键会落空
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash AS \"password_hash\" FROM sys_user WHERE username=?", username);
        String hash = encoder.encode(rawPassword);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO sys_user(username, password_hash, nickname, role) VALUES (?,?,?,?)",
                    username, hash, nickname, role);
            log.info("已创建演示账号 {}（角色 {}）", username, role);
            return;
        }
        Object currentHash = rows.get(0).get("password_hash");
        boolean placeholder = String.valueOf(currentHash).contains(DUMMY_HASH_MARK);
        if (placeholder || explicit) {
            jdbc.update("UPDATE sys_user SET password_hash=?, role=? WHERE username=?", hash, role, username);
            if (placeholder) {
                log.info("检测到 {} 的占位哈希，已重置为真实 BCrypt 哈希", username);
            }
        }
    }
}
