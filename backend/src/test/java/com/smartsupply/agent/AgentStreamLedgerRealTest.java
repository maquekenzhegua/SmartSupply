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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb-stream-real;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.flyway.enabled=false",
  "spring.sql.init.mode=never",
  "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
  // api-key 占位必须有非空兜底：类级 @Sql/@DirtiesContext 会先于方法级 EVAL_REAL_LLM
  // 门禁触发 Spring 上下文创建，无 env 时 Spring AI 因空 key 拒绝建上下文
  "spring.ai.openai.api-key=${OPENAI_API_KEY:dummy-real-gated-context}",
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
    @org.springframework.beans.factory.annotation.Value("${local.server.port:0}") int port;

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

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void streamRunAndToolCallsLandInLedger() throws Exception {
        String token = login();
        String sessionId = "stream-ledger-" + System.currentTimeMillis();
        String sse = streamChat(token, sessionId, sessionId);
        assertTrue(sse.contains("event:done"), "流式必须正常完成");
        Long runId = jdbc.queryForObject(
                "SELECT id FROM agent_run WHERE session_id=? AND mode='stream' ORDER BY id DESC LIMIT 1",
                Long.class, sessionId);
        String status = jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, runId);
        assertEquals("SUCCESS", status, "流式 run 必须在台账收口（此前 java-direct 流式完全无 run 行）");
        String toolJson = jdbc.queryForObject(
                "SELECT tool_calls_json FROM chat_message WHERE session_id=" +
                        "(SELECT id FROM chat_session WHERE session_key=?) AND role='assistant' ORDER BY id DESC LIMIT 1",
                String.class, sessionId);
        assertFalse(toolJson == null || toolJson.isBlank(), "工具调用清单必须挂到本轮 assistant 行");
        Integer toolRows = jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?", Integer.class, runId);
        assertTrue(toolRows != null && toolRows > 0, "流式工具调用必须进 agent_tool_call 台账");
    }

    /** 客户端断连兜底：读首个 token 后立即断开（真实模型 TTFB ~20s+，流必然未完成），
     *  断开触发 onError 兜底——user + 已生成部分回复必须落库，run 收口 ABORTED。
     *  此前断连什么都不落（onComplete 不执行），下一轮上下文直接丢本轮。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void clientAbortStillSavesPartialTurn() throws Exception {
        String token = login();
        String sessionId = "stream-abort-" + System.currentTimeMillis();
        String body = om.writeValueAsString(Map.of(
                "message","用三段话分别介绍华南中心仓和华东中心仓的库存管理要点，内容尽量详尽",
                "agentType","general","sessionId",sessionId));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/agent/chat/stream"))
                .header("Authorization","Bearer "+token)
                .header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(120))
                .build();
        String firstTokenChunk;
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        try {
            assertEquals(200, resp.statusCode());
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line; String firstData = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) { firstData = line.substring(5); break; }
                }
                assertTrue(firstData != null && !firstData.isBlank(), "断开前必须收到至少一个 token");
                firstTokenChunk = firstData;
            }
        } finally {
            resp.body().close();   // 关闭底层流即断开连接（触发服务端 onError 兜底）
        }
        // 断开已发生：轮询断连兜底落库（user + partial assistant + run ABORTED）
        String firstChunk = firstTokenChunk;
        boolean assistantSaved = awaitRow(() -> {
            String c = jdbc.queryForObject(
                    "SELECT content FROM chat_message WHERE session_id=" +
                            "(SELECT id FROM chat_session WHERE session_key=?) AND role='assistant' ORDER BY id DESC LIMIT 1",
                    String.class, sessionId);
            return c != null && !c.isBlank() && c.startsWith(firstChunkPrefix(firstTokenChunk));
        }, 40);
        assertTrue(assistantSaved, "断连兜底必须落 user+partial assistant（onComplete 不执行的路径）");
        String abortStatus = awaitValue(() -> jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE session_id=? AND mode='stream' ORDER BY id DESC LIMIT 1",
                String.class, sessionId), "ABORTED", 40);
        assertEquals("ABORTED", abortStatus, "断连的 run 必须以 ABORTED 收口而非 SUCCESS");
    }

    private String firstChunkPrefix(String chunk) {
        return chunk == null ? "" : chunk;
    }

    private boolean awaitRow(java.util.function.Supplier<Boolean> check, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try { if (Boolean.TRUE.equals(check.get())) return true; } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        return false;
    }

    private String awaitValue(java.util.function.Supplier<String> check, String want, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try { String v = check.get(); if (want.equals(v)) return v; } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        return check.get();
    }
}
