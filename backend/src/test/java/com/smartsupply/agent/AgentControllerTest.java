package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb2;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AgentControllerTest {
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

    @Test void agentChatReturnsReply() throws Exception {
        String token = login();
        mvc.perform(post("/api/agent/chat")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("message","查询低库存SKU","agentType","general","sessionId","t1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
    }
    @Test void agentChatRejectsEmptyMessage() throws Exception {
        String token = login();
        mvc.perform(post("/api/agent/chat")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("message","","agentType","general"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 实跑事故回归：java-direct 非流式路径此前只 append user 消息，assistant 回复
     *  既不进会话记忆也不进 chat_message 台账（deep/流式路径都有 append，唯独它没有）——
     *  下一轮对话上下文看不到上一轮回答，tool_calls_json 还会错挂到上一轮旧行。 */
    @Test void agentChatPersistsAssistantReply() throws Exception {
        String token = login();
        String sid = "memfix-" + System.currentTimeMillis();
        String res = mvc.perform(post("/api/agent/chat")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("message","查询低库存SKU","agentType","general","sessionId",sid))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reply = om.readTree(res).path("data").path("reply").asText();
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=" +
                        "(SELECT id FROM chat_session WHERE session_key=?) AND role='assistant'",
                Integer.class, sid);
        assertEquals(1, cnt, "chat() 完成后 assistant 回复必须落 chat_message 台账");
        String content = jdbc.queryForObject(
                "SELECT content FROM chat_message WHERE session_id=" +
                        "(SELECT id FROM chat_session WHERE session_key=?) AND role='assistant'",
                String.class, sid);
        assertEquals(reply, content, "落库内容必须与响应 reply 严格一致（引用增强后的最终回复）");
    }
}
