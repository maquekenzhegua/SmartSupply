package com.smartsupply.agent;

import com.smartsupply.agent.memory.ChatMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatMemoryService 回归（并发修复验证）：
 * 旧实现把消息列表存成单个 JSON 字符串、append 为读-改-写，并发 append 会互相覆盖丢消息；
 * 新实现 RPUSH 原子 append + Lua 原子裁剪，本类用真实 Redis 断言并发不丢、窗口裁剪生效、
 * 会话创建即归属（canAccess 隔离）。
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb3;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.flyway.enabled=false",
  "spring.sql.init.mode=never",
  "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
  "spring.ai.openai.api-key=dummy-test-key-for-ci", "smartsupply.ai.mock=true",
  "spring.ai.vectorstore.pgvector.dimensions=1024"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatMemoryServiceTest {
    private static final String SID = "memtest-" + System.currentTimeMillis();

    @Autowired ChatMemoryService memory;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys("agent:memory:*" + SID + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void concurrentAppendLosesNoMessages() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.execute(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ignored) { return; }
                memory.append(SID, "user", "m" + idx, "admin");
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发 append 应在 30s 内完成");
        pool.shutdown();

        ChatMemoryService.MemorySnapshot snap = memory.snapshot(SID, "");
        // 关键断言：20 条并发消息一条不丢（旧读-改-写实现下该断言随机失败）
        assertEquals(n, snap.messages().size(),
                "并发 append 不得丢消息，实际 " + snap.messages().size() + " 条");
        assertEquals("user", snap.messages().get(0).get("role"));
    }

    @Test
    void windowTrimWritesSummary() {
        for (int i = 0; i < 45; i++) {
            memory.append(SID, i % 2 == 0 ? "user" : "assistant", "msg-" + i, "admin");
        }
        Long len = redis.opsForList().size("agent:memory:" + SID);
        assertNotNull(len);
        assertTrue(len <= 40, "窗口裁剪后列表不得超过 40 条，实际 " + len);
        String summary = redis.opsForValue().get("agent:memory:summary:" + SID);
        assertNotNull(summary, "被裁掉的旧消息应产生滚动摘要（双写）");
        assertFalse(summary.isBlank());
    }

    @Test
    void sessionCreatedWithOwner() {
        memory.append(SID, "user", "hello", "admin");
        String owner = memory.sessionOwner(SID);
        assertEquals("admin", owner, "新会话应在创建时即归属首个写入者");
        assertTrue(memory.canAccess(SID, "admin"));
        assertFalse(memory.canAccess(SID, "ops"), "非归属者不得访问他人会话");
    }
}
