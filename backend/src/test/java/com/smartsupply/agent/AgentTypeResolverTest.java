package com.smartsupply.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** agent_type 服务端裁决：客户端已知类型采信，auto/未知值关键词归类。 */
class AgentTypeResolverTest {

    private final AgentTypeResolver resolver = new AgentTypeResolver();

    @Test
    void knownClientTypesTrusted() {
        for (String t : new String[]{"general", "contract", "replenishment", "bi"}) {
            AgentTypeResolver.Resolved r = resolver.resolve(t, "任意消息");
            assertEquals(t, r.agentType());
            assertEquals("client", r.source());
        }
    }

    @Test
    void autoClassifiedByKeywords() {
        assertEquals("contract", resolver.resolve("auto", "这份合同的违约金条款有风险吗").agentType());
        assertEquals("replenishment", resolver.resolve("auto", "看下哪些SKU低于安全库存需要补货").agentType());
        assertEquals("bi", resolver.resolve("auto", "给我看本月销售趋势报表").agentType());
        assertEquals("general", resolver.resolve("auto", "你好").agentType());
        assertEquals("classified", resolver.resolve("auto", "你好").source());
    }

    @Test
    void unknownAndBlankValuesClassified() {
        assertEquals("replenishment", resolver.resolve("nonsense-type", "查库存").agentType());
        assertEquals("general", resolver.resolve("", "你好").agentType());
        assertEquals("classified", resolver.resolve("nonsense-type", "查库存").source());
    }

    @Test
    void contractKeywordsWinOverReplenishment() {
        // 同时命中合同与采购词时按声明顺序：合同优先（风控场景误判代价更高）
        assertEquals("contract", resolver.classify("采购合同风控审查"));
    }
}
