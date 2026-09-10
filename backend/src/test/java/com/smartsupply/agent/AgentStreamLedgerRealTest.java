package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 LLM 流式台账验证（默认跳过，不进 CI；与 AgentUsageRealTest 同门控模式）：
 * 跑法：EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=... AI_MODEL=mimo-v2.5 \
 *        mvn -B test -Dtest=AgentStreamLedgerRealTest
 *
 * 验证两件事（实跑缺陷修复）：
 * 1. 流式工具追踪：ThreadLocal 实现在流式下必丢（begin 在 sseExecutor 线程、
 *    工具执行在 reactor 线程）——探针实锤 done.tools=[] 而回复里有真实工具数据；
 *    ToolContext 共享列表修复后 done 事件 tools 必须非空。
 * 2. 流式台账：java-direct 流式此前完全无 agent_run 行 / 无 tool_calls_json /
 *    无 insertToolCall / 无 recordRag / 无预算入账——修复后与 /chat 同口径落库。
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb-stream-real;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.flyway.enabled=false",
  "spring.sql.init.mode=never",
  "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
  "spring.ai.openai.api-key=${OPENAI_API_KEY:}",
  "spring.ai.openai.base-url=${OPENAI_BASE_URL:https://api.openai.com}",
  "spring.ai.openai.chat.options.model=${AI_MODEL:gpt-4o-mini}",
  "smartsupply.ai.mock=false",
  "spring.ai.vectorstore.pgvector.dimensions=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AgentStreamLedgerRealTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","admin123"))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    /** 发起流式对话并等待完成，返回完整 SSE 文本 */
    private String streamChat(String token, String message, String sessionId) throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/chat/stream")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "message","查询SKU-T001-WH-M现在的库存数量是多少？","agentType","general","sessionId",sessionId))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        return dispatched.getResponse().getContentAsString();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void streamDoneEventReportsToolTrace() throws Exception {
        String token = login();
        String sessionId = "stream-tools-" + System.currentTimeMillis();
        String sse = streamChat(token, sessionId, sessionId);
        String doneLine = java.util.Arrays.stream(sse.split("\n"))
                .filter(l -> l.startsWith("data:{\"ttfbMs"))
                .reduce((a, b) -> b).orElse("");
        assertFalse(doneLine.isEmpty(), "流式必须产出 done 遥测帧");
        @SuppressWarnings("unchecked")
        Map<String, Object> done = om.readValue(doneLine.substring(5), Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> tools = (java.util.List<String>) done.get("tools");
        assertFalse(tools == null || tools.isEmpty(),
                "流式工具追踪跨线程修复后 done.tools 必须非空（getInventory 实际执行）");
        assertTrue(done.get("tokenSource") != null);
    }
}
