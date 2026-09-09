package com.smartsupply.agent;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 成本闸门（B3）集成回归：日预算超限 /chat 直接拒绝（Result.code=429），
 * 未超限正常放行。Redis 不可用环境走 DB 台账口径——预插一条高成本 run 触发超限。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-budget;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "spring.ai.openai.api-key=dummy-test-key-for-ci", "smartsupply.ai.mock=true",
        "smartsupply.agent.daily-cost-limit-usd=1.0",
        "spring.ai.vectorstore.pgvector.dimensions=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AgentBudgetGateTest {

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
    void normalChatPassesWhenUnderBudget() throws Exception {
        String token = login();
        mvc.perform(post("/api/agent/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("message", "你好，介绍一下系统"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @Order(2)
    void overBudgetChatRejectedWith429() throws Exception {
        String token = login();
        // DB 台账口径触发：当日 admin 累计成本超 1.0（Redis 不可达时 check() 走 DB SUM 回退）
        jdbc.update("INSERT INTO agent_run(trace_id, username, session_id, agent_type, mode, status, cost_usd, created_at) " +
                        "VALUES ('t-budget-gate', 'admin', 's-budget', 'general', 'java-direct', 'SUCCESS', 9.9, now())");
        mvc.perform(post("/api/agent/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("message", "你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("预算已用尽")));
    }
}
