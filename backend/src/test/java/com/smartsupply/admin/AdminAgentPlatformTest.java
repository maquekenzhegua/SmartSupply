package com.smartsupply.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 平台化闭环集成回归：
 * 1) Prompt 中心：Admin 发布 → /prompts/effective 显示 db 生效 → /chat 响应 promptVersion=发布版
 *    （此前激活只改表、运行时不读，激活是摆设——这条测试把闭环钉死）；
 * 2) 在线评测闭环：低分反馈 → /eval/candidates 带出提问原文 → /export 出 golden JSONL；
 * 3) 评测快照：POST 落库 → GET 返回结构化 metrics。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-adminplatform;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "spring.ai.openai.api-key=dummy-test-key-for-ci", "smartsupply.ai.mock=true",
        "spring.ai.vectorstore.pgvector.dimensions=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AdminAgentPlatformTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("data").get("token").asText();
    }

    @Test
    @Order(1)
    void promptPublishEffectiveAndChatActuallyUsesIt() throws Exception {
        String token = login();
        // 1. 发布 general 新版本（走 PromptRegistry.publish：DB 落库 + 缓存失效）
        mvc.perform(post("/api/admin/agent/prompts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "agentType", "general", "version", "v9.9",
                                "content", "集成测试发布的人设：测试发布人设标记XYZ。其余规则同 v1.6。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 2. 生效视图显示 db 来源
        mvc.perform(get("/api/admin/agent/prompts/effective").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.general.version").value("v9.9"))
                .andExpect(jsonPath("$.data.general.source").value("db"));
        // 3. 对话真实使用发布版（响应 promptVersion 归因字段）
        mvc.perform(post("/api/agent/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("message", "你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promptVersion").value("v9.9"));
        // 4. 台账归因：agent_run.prompt_version 落发布版
        String ver = jdbc.queryForObject(
                "SELECT prompt_version FROM agent_run WHERE prompt_version='v9.9' LIMIT 1", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("v9.9", ver);
    }

    @Test
    @Order(2)
    void lowRatingFeedbackBecomesEvalCandidateWithQuestion() throws Exception {
        String token = login();
        // 造链路：会话 → user 提问 → assistant 回复 → 负反馈指向 assistant 消息
        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        jdbc.update("INSERT INTO chat_session(agent_type, title, session_key, user_id) VALUES ('general','t-cand','t-cand',?)", adminId);
        Long sid = jdbc.queryForObject("SELECT id FROM chat_session WHERE session_key='t-cand'", Long.class);
        jdbc.update("INSERT INTO chat_message(session_id, role, content) VALUES (?, 'user', '低库存的SKU怎么补货')", sid);
        jdbc.update("INSERT INTO chat_message(session_id, role, content) VALUES (?, 'assistant', '（不相关的回答）')", sid);
        Long msgId = jdbc.queryForObject(
                "SELECT id FROM chat_message WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1", Long.class, sid);
        jdbc.update("INSERT INTO user_feedback(message_id, session_id, rating, comment) VALUES (?, 't-cand', -1, '答非所问')", msgId);

        MvcResult res = mvc.perform(get("/api/admin/agent/eval/candidates")
                        .header("Authorization", "Bearer " + token)
                        .param("maxRating", "-1").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("低库存的SKU怎么补货"), "候选必须带出提问原文: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("答非所问"));

        // 导出 NDJSON（ground_truth 留空待人工标注）
        MvcResult exp = mvc.perform(get("/api/admin/agent/eval/candidates/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String ndjson = exp.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(ndjson.contains("\"layer\":\"online\""));
        org.junit.jupiter.api.Assertions.assertTrue(ndjson.contains("\"ground_truth\":\"\""));
    }

    @Test
    @Order(3)
    void evalSnapshotPostThenListParsesMetrics() throws Exception {
        String token = login();
        mvc.perform(post("/api/admin/agent/eval/snapshots")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "source", "llm-judge-mock", "reportFile", "docs/eval-report-mock-ci.md",
                                "metrics", Map.of("avg_keyword_hit", 0.73, "avg_faithfulness", 1.7)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/admin/agent/eval/snapshots").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("llm-judge-mock"))
                .andExpect(jsonPath("$.data[0].metrics.avg_keyword_hit").value(0.73));
    }
}
