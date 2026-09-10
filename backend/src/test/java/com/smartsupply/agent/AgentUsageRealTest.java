package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 LLM 用量回填验证（默认跳过，不进 CI；与 MuseSparkChatModelRealTest 同门控模式）：
 * 跑法：EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=... AI_MODEL=mimo-v2.5 \
 *        mvn -B test -Dtest=AgentUsageRealTest
 * 验证：OpenAI 兼容网关的非流式 chat.completions 响应携带 usage，ChatModel 解析进
 * ChatResponse 元数据；/api/agent/chat 台账 tokenSource 应为 actual 而非 estimated。
 * （同网关同模型下 python-deep 链路早已 actual，java-direct 此前恒 estimated——实跑实锤。）
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb-usage-real;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
class AgentUsageRealTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","admin123"))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void agentChatLedgerReportsActualUsageOnRealModel() throws Exception {
        String token = login();
        String res = mvc.perform(post("/api/agent/chat")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "message","查询SKU-T001-WH-M现在的库存数量是多少？","agentType","general",
                        "sessionId","usage-real-" + System.currentTimeMillis()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        // 台账/响应 tokenSource 必须是 actual：usage 来自网关响应而非长度估算
        assertEquals("actual", om.readTree(res).path("data").path("tokenSource").asText(),
                "真实模型非流式对话应回填 actual usage");
    }
}
