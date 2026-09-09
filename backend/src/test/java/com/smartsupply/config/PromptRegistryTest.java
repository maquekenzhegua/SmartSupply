package com.smartsupply.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt 管理中心闭环的单测：DB 生效版优先、内置兜底、DB 故障降级、缓存失效重载。
 * 此前 prompt_version 表只写不读——"激活/回滚"不改运行时行为；现在读路径有测试钉死。
 */
class PromptRegistryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @SuppressWarnings("unchecked")
    private void stubActive(String version, String content) {
        when(jdbc.queryForList(anyString(), eq("contract")))
                .thenReturn(List.of(Map.of("version", version, "content", content)));
    }

    @Test
    void dbActiveVersionWinsOverBuiltin() {
        stubActive("v9.9", "DB发布的风控提示词");
        PromptRegistry r = new PromptRegistry(jdbc);
        assertEquals("v9.9", r.versionFor("contract"));
        assertEquals("DB发布的风控提示词", r.contentFor("contract"));
        assertEquals("db", r.get("contract").source());
    }

    @Test
    void builtinWhenNoActiveRow() {
        when(jdbc.queryForList(anyString(), eq("contract"))).thenReturn(List.of());
        PromptRegistry r = new PromptRegistry(jdbc);
        PromptRegistry.PromptVersion v = r.get("contract");
        assertEquals("v3.0", v.version());
        assertEquals("builtin", v.source());
    }

    @Test
    void honestFallbackWhenDbDown() {
        when(jdbc.queryForList(anyString(), eq("contract"))).thenThrow(new RuntimeException("connection refused"));
        PromptRegistry r = new PromptRegistry(jdbc);
        PromptRegistry.PromptVersion v = r.get("contract");
        assertEquals("v3.0", v.version());
        assertEquals("builtin", v.source());
        // 熔断窗口内的后续读取不再撞库（也不抛异常）
        assertEquals("v3.0", r.versionFor("contract"));
    }

    @Test
    void evictReloadsFromDb() {
        stubActive("v9.9", "第一版");
        PromptRegistry r = new PromptRegistry(jdbc);
        assertEquals("v9.9", r.versionFor("contract"));
        stubActive("v10.0", "第二版");
        r.evict("contract");
        assertEquals("v10.0", r.versionFor("contract"));
    }

    @Test
    void publishWritesDbAndEvicts() {
        when(jdbc.update(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(jdbc.update(anyString(), eq("contract"))).thenReturn(1);
        stubActive("v11.0", "发布版");
        PromptRegistry r = new PromptRegistry(jdbc);
        PromptRegistry.PromptVersion pv = r.publish("contract", "v11.0", "发布版", "admin");
        assertEquals("v11.0", pv.version());
        assertEquals("db", pv.source());
        assertEquals("v11.0", r.versionFor("contract"));
    }

    @Test
    void deepMapsToGeneralPersona() {
        when(jdbc.queryForList(anyString(), eq("general"))).thenReturn(List.of());
        PromptRegistry r = new PromptRegistry(jdbc);
        assertEquals(r.versionFor("general"), r.versionFor("deep"));
        assertEquals(r.contentFor("general"), r.contentFor("deep"));
    }

    @Test
    void effectiveAllExposesSourceForClosureVerification() {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        PromptRegistry r = new PromptRegistry(jdbc);
        Map<String, PromptRegistry.PromptVersion> all = r.effectiveAll();
        assertTrue(all.containsKey("general"));
        assertEquals("builtin", all.get("general").source());
    }

    @Test
    void nullJdbcRunsPureBuiltin() {
        PromptRegistry r = new PromptRegistry();
        assertEquals("v1.6", r.versionFor("general"));
        assertEquals("builtin", r.get("general").source());
    }
}
