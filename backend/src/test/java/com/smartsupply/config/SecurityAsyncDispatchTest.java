package com.smartsupply.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 流结束后的安全链回归：SseEmitter 完成时容器做 ASYNC dispatch，Boot 3.5 默认
 * 在全部 dispatch 类型上重跑 springSecurityFilterChain，而 JwtAuthFilter 继承
 * OncePerRequestFilter（默认跳过 ASYNC/ERROR），此时链上处于匿名态——若
 * authorizeHttpRequests 不放行 dispatch 类型，AuthorizationFilter 会拒绝并因
 * 响应已提交而抛 "Unable to handle the Spring Security Exception"。
 * ERROR dispatch 同理：错误页 /error 的渲染也不能被拒（Security 官方文档口径）。
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb-sse;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
class SecurityAsyncDispatchTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","admin123"))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    @Test void asyncDispatchAfterSseMustPassSecurityChain() throws Exception {
        String token = login();
        MvcResult result = mvc.perform(post("/api/agent/chat/stream")
                .header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "message","查询低库存SKU","agentType","general","sessionId","sse-sec-t1"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());
    }

    @Test void errorDispatchMustNotBeDenied() throws Exception {
        mvc.perform(get("/error").with(req -> {
                    req.setDispatcherType(DispatcherType.ERROR);
                    return req;
                }))
                .andExpect(status().isInternalServerError());
    }
}
