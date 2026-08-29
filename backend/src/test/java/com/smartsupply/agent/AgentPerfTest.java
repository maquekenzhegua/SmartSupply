package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Agent 性能基线：并发延迟 P50/P95/P99、吞吐、成功率，STAR 中 Result 的量化依据。
 * 离线可跑（H2 + MockChatModel，无真实 LLM/PG/Redis），结果写入 surefire 输出供报告采集。
 * 面试可讲：Mock 下测的是编排层开销，真 LLM 接入后在相同压测脚本上对比即可得真实 P95。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-perf;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "spring.ai.openai.api-key=dummy", "smartsupply.ai.mock=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AgentPerfTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    private long singleCall(String t, String msg) throws Exception {
        long s = System.nanoTime();
        MvcResult r = mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("message", msg, "agentType", "general", "sessionId", "perf-" + Thread.currentThread().getId()))))
                .andReturn();
        long e = System.nanoTime();
        int code = om.readTree(r.getResponse().getContentAsString()).path("code").asInt(0);
        if (code != 200) throw new AssertionError("chat failed code=" + code + " body=" + r.getResponse().getContentAsString());
        return (e - s) / 1_000_000L; // ms
    }

    private static String fmt(List<Long> sorted) {
        if (sorted.isEmpty()) return "n/a";
        long p50 = sorted.get((int) (sorted.size() * 0.50));
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        long p99 = sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long min = sorted.get(0), max = sorted.get(sorted.size() - 1);
        return String.format("min=%dms p50=%dms avg=%.1fms p95=%dms p99=%dms max=%dms n=%d", min, p50, avg, p95, p99, max, sorted.size());
    }

    @Test
    void agentChatLatencyP50P95AndThroughput() throws Exception {
        String t = token();
        // warmup
        for (int i = 0; i < 5; i++) singleCall(t, "warmup " + i);

        int total = 120;
        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String msg = (idx % 3 == 0) ? "查询低库存SKU" : (idx % 3 == 1) ? "分析合同风险 乙方承担一切连带责任" : "华南区哪个品类退货率最高";
                    long ms = singleCall(t, msg + " #" + idx);
                    lat.add(ms);
                } catch (Throwable e) { errs.add(e); } finally { done.countDown(); }
            });
        }
        long wall0 = System.nanoTime();
        start.countDown();
        boolean ok = done.await(90, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - wall0) / 1_000_000L;
        pool.shutdownNow();

        assertThat(ok).as("perf tasks must finish in 90s").isTrue();
        assertThat(errs).as("errors: " + errs).isEmpty();
        assertThat(lat).hasSize(total);

        List<Long> sorted = new ArrayList<>(lat);
        Collections.sort(sorted);
        double throughput = total * 1000.0 / Math.max(1, wallMs);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        long max = sorted.get(sorted.size() - 1);

        System.out.println("[AgentPerf] wall=" + wallMs + "ms throughput=" + String.format("%.1f", throughput) + " req/s " + fmt(sorted));
        System.out.println("[AgentPerf] successRate=100% (" + total + "/" + total + ") concurrency=" + concurrency);

        // Mock 编排层应极快；阈值宽松以免 CI 抖动误判，真 LLM 接入后单独基线
        assertThat(p95).as("p95 should be < 1500ms on Mock").isLessThan(1500);
        assertThat(max).isLessThan(3000);
        assertThat(throughput).isGreaterThan(5);
    }

    @Test
    void biAnalyzeLatency() throws Exception {
        String t = token();
        List<Long> lat = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long s = System.nanoTime();
            mvc.perform(post("/api/bi/analyze").header("Authorization", "Bearer " + t)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("question", "查询所有供应商评分"))))
                    .andExpect(r -> assertThat(om.readTree(r.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200));
            lat.add((System.nanoTime() - s) / 1_000_000L);
        }
        Collections.sort(lat);
        System.out.println("[BiPerf] " + fmt(lat));
        assertThat(lat.get((int) Math.ceil(lat.size() * 0.95) - 1)).isLessThan(1200);
    }

    @Test
    void inventoryListLatency() throws Exception {
        String t = token();
        List<Long> lat = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long s = System.nanoTime();
            mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer " + t)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("message", "查库存", "agentType", "general", "sessionId", "perf-inv"))))
                    .andReturn();
            // also hit inventory directly
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/inventory/low-stock")
                    .header("Authorization", "Bearer " + t)).andReturn();
            lat.add((System.nanoTime() - s) / 1_000_000L);
        }
        Collections.sort(lat);
        System.out.println("[InventoryPerf] " + fmt(lat));
    }
}
