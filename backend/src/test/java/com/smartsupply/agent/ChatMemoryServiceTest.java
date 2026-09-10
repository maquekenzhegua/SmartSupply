package com.smartsupply.agent;

import com.smartsupply.agent.memory.ChatMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.lang.reflect.Field;
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

    /** 锁 Lua 裁剪脚本契约：len<=keep（无可裁剪）必须返回空列表——
     *  旧实现 `return nil` 被 Lettuce 反序列化成 size=1 的 [null]，
     *  驱动 append 里的压缩块对非法条目 NPE（每次 append 必触发）。 */
    @Test
    @SuppressWarnings("unchecked")
    void trimScriptReturnsEmptyListWhenUnderWindow() throws Exception {
        String t1 = SID + "-t1";
        String k = "agent:memory:" + t1;
        redis.delete(k);
        for (int i = 0; i < 5; i++) redis.opsForList().rightPush(k, "{\"role\":\"user\",\"content\":\"m" + i + "\"}");
        try {
            Field f = ChatMemoryService.class.getDeclaredField("TRIM_OLD_SCRIPT");
            f.setAccessible(true);
            DefaultRedisScript<List> script = (DefaultRedisScript<List>) f.get(null);
            List<Object> old = redis.execute(script, List.of(k), "40", "604800");
            assertNotNull(old, "Lua 空表(无可裁剪)应反序列化为列表而非 null");
            assertTrue(old.isEmpty(),
                    "len<=WINDOW 时脚本应返回空列表（[null] 会让压缩块把 null 当消息解析），实际 size=" + old.size());
        } finally {
            redis.delete(k);
        }
    }

    /** 锁损坏条目防御：列表头部混入字面量 "null"（历史脏数据），真裁剪发生时
     *  压缩链路不得因 null 元素中断——旧实现该场景 LTRIM 已发生但摘要双写被跳过，
     *  是唯一真实丢摘要的路径（本地实测每次 append 都触发的一对 NPE）。 */
    @Test
    void corruptEntryTrimStillWritesSummary() {
        String sid2 = SID + "-t2";
        String k = "agent:memory:" + sid2;
        redis.delete(k);
        redis.opsForList().rightPush(k, "null");  // 字面量损坏条目（最老位置）
        for (int i = 0; i < 40; i++) {
            redis.opsForList().rightPush(k, "{\"role\":\"user\",\"content\":\"m" + i + "\"}");
        }
        try {
            memory.append(sid2, "user", "trigger", "admin");  // len=42>40，真裁剪最老 2 条（含 "null"）
            Long len = redis.opsForList().size(k);
            assertNotNull(len);
            assertTrue(len <= 40, "窗口裁剪后列表不得超过 40 条，实际 " + len);
            String summary = redis.opsForValue().get("agent:memory:summary:" + sid2);
            assertNotNull(summary, "裁剪发生时滚动摘要必须写入（不得因损坏条目中断）");
            assertFalse(summary.isBlank());
        } finally {
            redis.delete(k);
            redis.delete("agent:memory:summary:" + sid2);
        }
    }
}
