package com.smartsupply.module.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.flyway.enabled=false",
  "spring.sql.init.mode=never",
  "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
  "spring.ai.openai.api-key=dummy-test-key-for-ci", "smartsupply.ai.mock=true",
  "spring.ai.vectorstore.pgvector.dimensions=1536"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Test void loginSuccessReturnsToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
    @Test void loginFailReturns401Code() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","wrong"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }
    @Test void authenticatedEndpointRequiresJwt() throws Exception {
        mvc.perform(get("/api/suppliers")).andExpect(status().isForbidden());
    }
    @Test void authenticatedFlowWorks() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("username","admin","password","admin123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = om.readTree(body).path("data").path("token").asText();
        mvc.perform(get("/api/suppliers").header("Authorization","Bearer "+token))
                .andExpect(status().isOk());
    }
    @Test void traceIdHeaderEchoed() throws Exception {
        mvc.perform(get("/api/auth/me").header("X-Trace-Id","test-trace-123"))
                .andExpect(header().string("X-Trace-Id","test-trace-123"));
        // auto-generated when absent
        mvc.perform(get("/api/auth/me"))
                .andExpect(header().exists("X-Trace-Id"));
    }
}
