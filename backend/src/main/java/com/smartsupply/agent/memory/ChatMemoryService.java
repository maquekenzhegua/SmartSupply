package com.smartsupply.agent.memory;

import com.smartsupply.agent.TokenEstimator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 短期记忆 + DB 长期记忆 + 摘要压缩 + token 预算窗口。
 * 短期：Redis agent:memory:{sessionId}（List 结构）7天，窗口按 token 预算裁剪（缺省 4000 token，0=退回固定 20 条）。
 *   并发修复：旧实现把消息列表存成单个 JSON 字符串，append 是"读-改-写"非原子——并发 append 会互相覆盖丢消息；
 *   现改为 Redis List，每条消息 RPUSH（天然原子），超限裁剪与摘要提取由 Lua 脚本原子完成。
 * 长期：chat_session + chat_message 落库，可审计可恢复；会话以 session_key 等值定位（title 反查仅兜底）。
 *   会话修复：创建会话的 INSERT 直接带 user_id（此前先建无主会话靠"首个写入者认领"，
 *   认领前存在 IDOR 窗口）；存量无主会话仍保留认领兜底。
 * 摘要：超过40条时压缩老消息为 summary——Redis 与 chat_session.summary 双写（Redis 丢失可从 DB 恢复）。
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    /** 滚动窗口容量：列表内最多保留的最新消息数，超出的旧消息交给摘要压缩 */
    private static final long WINDOW = 40;
    private static final long TTL_SECONDS = 7 * 24 * 3600;

    /** 原子裁剪脚本：返回被裁掉的旧消息（供摘要压缩），LTRIM 与读取在 Lua 内原子完成。
     *  此前"LRANGE 再 LTRIM"两步分离，两个并发请求可能重复压缩/互相覆盖摘要。
     *  len<=keep 时必须返回 Lua 空表 `{}` 而非 nil：Lettuce 把 nil multi-bulk 反序列化成
     *  size=1 的 [null] 列表，`!old.isEmpty()` 判真后压缩链路会把 null 当消息解析 NPE
     *  （每次 append 必触发）；空表 → RESP 空 multi-bulk → Java 空列表，语义即"无可裁剪"。 */
    private static final DefaultRedisScript<List> TRIM_OLD_SCRIPT = new DefaultRedisScript<>(
            "local len = redis.call('LLEN', KEYS[1]) " +
            "local keep = tonumber(ARGV[1]) " +
            "if len <= keep then return {} end " +
            "local old = redis.call('LRANGE', KEYS[1], 0, len - keep - 1) " +
            "redis.call('LTRIM', KEYS[1], len - keep, -1) " +
            "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "return old", List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final JdbcTemplate jdbc;
    private final TokenEstimator tokenEstimator;
    private final int tokenBudget;
    @Autowired(required=false) private ChatClient chatClient;

    public ChatMemoryService(StringRedisTemplate redis, ObjectMapper om, JdbcTemplate jdbc,
                             TokenEstimator tokenEstimator,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${smartsupply.agent.memory.token-budget:4000}") int tokenBudget) {
        this.redis = redis;
        this.om = om;
        this.jdbc = jdbc;
        this.tokenEstimator = tokenEstimator;
        this.tokenBudget = Math.max(0, tokenBudget);
    }

    private String key(String sessionId) { return "agent:memory:" + sessionId; }
    private String summaryKey(String sessionId) { return "agent:memory:summary:" + sessionId; }

    public record MemorySnapshot(List<Map<String, String>> messages, String summary) {}

    /** 会话定位：session_key 等值查询优先（唯一索引，确定性），title 反查仅作存量兜底。
     *  此前 title=sessionId 反查 + ORDER BY id DESC LIMIT 1 是脆弱路径（title 非键，同名即歧义）。 */
    private Long findSessionId(String sessionId) {
        try {
            return jdbc.queryForObject("SELECT id FROM chat_session WHERE session_key=? ORDER BY id DESC LIMIT 1", Long.class, sessionId);
        } catch (Exception ignored) {}
        try {
            return jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? ORDER BY id DESC LIMIT 1", Long.class, sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供编排层做会话定位：session_key 等值优先，title 反查仅兜底（替代旧的裸 title 反查）。 */
    public Long findSessionDbId(String sessionId) {
        return findSessionId(sessionId);
    }

    /** Redis 摘要缺失时从 DB 恢复（摘要双写的读路径）。 */
    private String loadSummary(String sessionId) {
        try {
            String s = redis.opsForValue().get(summaryKey(sessionId));
            if (s != null && !s.isBlank()) return s;
        } catch (Exception ignored) {}
        try {
            Long sid = findSessionId(sessionId);
            if (sid != null) {
                String s = jdbc.queryForObject("SELECT COALESCE(summary,'') FROM chat_session WHERE id=?", String.class, sid);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void persistSummary(String sessionId, String summary) {
        try {
            Long sid = findSessionId(sessionId);
            if (sid != null) {
                jdbc.update("UPDATE chat_session SET summary=? WHERE id=?", summary, sid);
            }
        } catch (Exception e) {
            log.debug("summary 持久化失败（Redis 仍可用）: {}", e.toString());
        }
    }

    /** token 预算窗口：从最新往回保留，预算内尽量多留（至少最近两条）；超预算的老消息交给摘要语义承接。
     *  tokenBudget<=0 时退回旧口径（最多 21 条含 system）。 */
    private List<Message> trim(List<Message> out) {
        int head = 0;
        while (head < out.size() && out.get(head) instanceof SystemMessage) head++;
        if (tokenBudget <= 0) {
            if (out.size() > 21) {
                List<Message> trimmed = new ArrayList<>(out.subList(0, head));
                trimmed.addAll(out.subList(Math.max(head, out.size() - 20), out.size()));
                return trimmed;
            }
            return out;
        }
        int used = 0;
        int keepFrom = out.size();
        for (int i = out.size() - 1; i >= head; i--) {
            String text = out.get(i).getText() == null ? "" : out.get(i).getText();
            int t = tokenEstimator.estimate(text);
            if (used + t > tokenBudget && i < out.size() - 2) break;  // 至少保留最近两条
            used += t;
            keepFrom = i;
        }
        if (keepFrom <= head) return out;
        List<Message> trimmed = new ArrayList<>(out.subList(0, head));
        trimmed.addAll(out.subList(keepFrom, out.size()));
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    public List<Message> load(String sessionId, String systemPrompt) {
        List<Message> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) out.add(new SystemMessage(systemPrompt));
        // 滚动摘要注入：Redis 缺失时从 DB 恢复（双写读路径）
        String summary = loadSummary(sessionId);
        if (summary != null && !summary.isBlank()) {
            out.add(new SystemMessage("[历史对话摘要] " + summary));
        }
        // 短期记忆读取：List 结构逐条反序列化（RPUSH append 天然原子，无读改写竞态）
        List<String> items = null;
        try { items = redis.opsForList().range(key(sessionId), 0, -1); } catch (Exception e) { items = null; }
        if (items != null && !items.isEmpty()) {
            boolean parsed = false;
            try {
                for (String item : items) {
                    Map<String, String> m = om.readValue(item, new TypeReference<Map<String, String>>() {});
                    String role = m.get("role");
                    String content = m.get("content");
                    if ("user".equals(role)) out.add(new UserMessage(content));
                    else if ("assistant".equals(role)) out.add(new AssistantMessage(content));
                }
                parsed = true;
            } catch (Exception ignored) {}
            if (parsed) return trim(out);
        }
        // Redis miss（或旧版单字符串格式，WRONGTYPE 已在上面落空）-> DB 恢复
        try {
            rebuildListFromDb(sessionId);
        } catch (Exception e) {
            log.debug("DB memory load fallback failed sessionId={}: {}", sessionId, e.toString());
        }
        return trim(out);
    }

    /** 从 DB 重建 Redis List（首次访问/旧格式迁移/缓存丢失后回写），恢复后回写 Redis 加速下次。 */
    private void rebuildListFromDb(String sessionId) {
        Long sessionDbId = findSessionId(sessionId);
        if (sessionDbId == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content FROM chat_message WHERE session_id=? ORDER BY id DESC LIMIT 40", sessionDbId);
        if (rows.isEmpty()) return;
        List<String> items = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> r = rows.get(i);
            items.add(messageJson(String.valueOf(r.get("role")), String.valueOf(r.get("content"))));
        }
        String k = key(sessionId);
        try {
            redis.delete(k);  // 清掉可能存在的旧版字符串格式（WRONGTYPE）或半写状态
            redis.opsForList().rightPushAll(k, items);
            redis.expire(k, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis rebuild failed sessionId={}: {}", sessionId, e.toString());
        }
    }

    private String messageJson(String role, String content) {
        try {
            return om.writeValueAsString(Map.of("role", role, "content", content == null ? "" : content));
        } catch (Exception e) {
            return "{\"role\":\"" + role + "\",\"content\":\"\"}";
        }
    }

    public void append(String sessionId, String role, String content) {
        append(sessionId, role, content, com.smartsupply.common.CurrentUser.username());
    }

    public void append(String sessionId, String role, String content, String username) {
        String k = key(sessionId);
        try {
            // RPUSH 单条消息：原子 append，并发不丢消息（旧实现读-改-写会互相覆盖）
            redis.opsForList().rightPush(k, messageJson(role, content));
            redis.expire(k, TTL_SECONDS, TimeUnit.SECONDS);
            // 超窗压缩：Lua 原子"取旧+LTRIM"，返回被裁掉的旧消息；摘要压缩（LLM/规则）在 Java 侧做，
            // 摘要 key 为覆盖写（幂等），并发压缩最多多算一次 LLM，不会丢消息
            List<Object> old = redis.execute(TRIM_OLD_SCRIPT, List.of(k),
                    String.valueOf(WINDOW), String.valueOf(TTL_SECONDS));
            if (old != null && !old.isEmpty()) {
                List<Map<String, String>> olds = new ArrayList<>();
                for (Object o : old) {
                    // 防御已入库的损坏条目（如字面量 "null" 字符串）：解析为 null 的条目跳过，
                    // 不让它打断压缩链路——此场景 LTRIM 已发生，中断意味着被裁消息丢且无摘要
                    Map<String, String> parsed = om.readValue(String.valueOf(o), new TypeReference<Map<String, String>>() {});
                    if (parsed != null) olds.add(parsed);
                }
                if (!olds.isEmpty()) {
                    String prev = null;
                    try { prev = redis.opsForValue().get(summaryKey(sessionId)); } catch (Exception ignored) {}
                    String compressed = compress(olds, prev);
                    try { redis.opsForValue().set(summaryKey(sessionId), compressed, 7, TimeUnit.DAYS); } catch (Exception ignored) {}
                    persistSummary(sessionId, compressed);  // 摘要双写：Redis 丢失/重启后可从 chat_session.summary 恢复
                }
            }
        } catch (Exception e) {
            // 旧版单字符串格式（WRONGTYPE）迁移：从 DB 重建 List 后重试一次 append
            if (String.valueOf(e).contains("WRONGTYPE")) {
                try {
                    rebuildListFromDb(sessionId);
                    redis.opsForList().rightPush(k, messageJson(role, content));
                    redis.expire(k, TTL_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e2) {
                    log.debug("Redis append retry after migration failed sessionId={}: {}", sessionId, e2.toString());
                }
            } else {
                log.debug("Redis append failed sessionId={}: {}", sessionId, e.toString());
            }
        }
        // DB 落库（不阻断主流程）
        try {
            Long sessionDbId = findSessionId(sessionId);
            if (sessionDbId == null) {
                try {
                    Long userId = resolveUserId(username);
                    // session_key 唯一键 + 创建即归属：消除"先建无主会话再认领"之间的 IDOR 窗口
                    jdbc.update("INSERT INTO chat_session(agent_type, title, session_key, user_id) VALUES (?,?,?,?)",
                            "general", sessionId, sessionId, userId);
                    sessionDbId = findSessionId(sessionId);
                } catch (Exception ex) {
                    log.debug("create chat_session failed: {}", ex.toString());
                }
            } else {
                // 存量会话补 session_key（幂等回填）
                try { jdbc.update("UPDATE chat_session SET session_key=? WHERE id=? AND session_key IS NULL", sessionId, sessionDbId); }
                catch (Exception ignored) {}
            }
            if (sessionDbId != null) {
                jdbc.update("INSERT INTO chat_message(session_id, role, content) VALUES (?,?,?)",
                        sessionDbId, role, content == null ? "" : content);
                // 认领存量无主会话（历史数据兜底）：新会话已在 INSERT 时归属
                Long claimUserId = resolveUserId(username);
                if (claimUserId != null) {
                    jdbc.update("UPDATE chat_session SET user_id=? WHERE id=? AND user_id IS NULL", claimUserId, sessionDbId);
                }
            }
        } catch (Exception e) {
            log.debug("DB append failed sessionId={}: {}", sessionId, e.toString());
        }
    }

    /**
     * 把最近一轮的工具调用清单挂到该会话最近一条 assistant 消息上（chat_message.tool_calls_json）。
     * 调用方（AgentController）保证：调用前刚完成 assistant 消息 append，子查询命中的就是新行。
     * 写入方言：tool_calls_json 在 PG 是 JSONB 列，字符串参数必须显式 CAST 才能写入
     * （真 PG 报 42804 → BadSqlGrammarException，H2 TEXT 列不识别 jsonb 类型名）；
     * 口径与 ObservationService.insertToolCall 的 args_json 一致（4fff26a 台账方言修复）。
     */
    public void persistAssistantToolCalls(Long sessionDbId, String toolCallsJson) {
        try {
            jdbc.update("UPDATE chat_message SET tool_calls_json=CAST(? AS jsonb) WHERE id=" +
                    "(SELECT id FROM chat_message WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1)",
                    toolCallsJson, sessionDbId);
        } catch (org.springframework.jdbc.BadSqlGrammarException castUnsupported) {
            // H2（测试/demo，tool_calls_json=TEXT）不识别 jsonb 类型名 → 回退裸参数直插
            jdbc.update("UPDATE chat_message SET tool_calls_json=? WHERE id=" +
                    "(SELECT id FROM chat_message WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1)",
                    toolCallsJson, sessionDbId);
        }
    }

    /** 会话归属者用户名；user_id 为空（存量会话）或查不到用户时返回 null */
    public String sessionOwner(String sessionId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT u.username AS \"username\" FROM chat_session s LEFT JOIN sys_user u ON u.id=s.user_id " +
                            "WHERE s.session_key=? OR s.title=? ORDER BY s.id DESC LIMIT 1", sessionId, sessionId);
            if (rows.isEmpty()) return null;
            Object uname = rows.get(0).get("username");
            return uname == null ? null : String.valueOf(uname);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 会话访问裁决：无主（新会话/存量未绑定）放行并由首次 append 认领；仅归属者本人放行。
     * /chat、/chat/stream、/memory/{id} 统一走这里，堵住"猜到 sessionId 即可续写/读取他人上下文"的 IDOR。
     */
    public boolean canAccess(String sessionId, String username) {
        String owner = sessionOwner(sessionId);
        return owner == null || (username != null && owner.equals(username));
    }

    private Long resolveUserId(String username) {
        if (username == null || username.isBlank() || "system".equals(username)) return null;
        try {
            return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        } catch (Exception e) {
            return null;
        }
    }

    private String compress(List<Map<String, String>> old, String prevSummary) {
        // Try LLM rolling summary, fallback to rule truncation
        if (chatClient != null) {
            try {
                StringBuilder prompt = new StringBuilder("将以下对话压缩为150字内摘要，保留关键事实：\n");
                if (prevSummary != null && !prevSummary.isBlank()) prompt.append("已有摘要: ").append(prevSummary).append("\n");
                for (Map<String,String> m : old) {
                    if (m == null) continue;  // 防御损坏条目（防御口径同 append 解析层）
                    prompt.append(m.get("role")).append(": ").append(m.get("content")).append("\n");
                }
                String summary = chatClient.prompt().user(prompt.toString()).call().content();
                if (summary != null && !summary.isBlank()) {
                    String s = summary.trim();
                    return s.length() > 800 ? s.substring(0,800) : s;
                }
            } catch (Exception e) { log.debug("LLM compress fallback: {}", e.toString()); }
        }
        StringBuilder sb = new StringBuilder();
        if (prevSummary != null && !prevSummary.isBlank()) sb.append(prevSummary).append(" | ");
        int cap = Math.min(old.size(), 10);
        for (int i = 0; i < cap; i++) {
            Map<String, String> m = old.get(i);
            if (m == null) continue;  // 防御损坏条目（防御口径同 append 解析层）
            String c = m.get("content");
            if (c != null && c.length() > 80) c = c.substring(0, 80) + "...";
            sb.append(m.get("role")).append(":").append(c).append("; ");
        }
        String s = sb.toString();
        return s.length() > 800 ? s.substring(0, 800) : s;
    }

    public MemorySnapshot snapshot(String sessionId, String systemPrompt) {
        List<Message> loaded = load(sessionId, systemPrompt);
        List<Map<String, String>> msgs = new ArrayList<>();
        for (Message m : loaded) {
            if (m instanceof SystemMessage) continue;
            String role = (m instanceof UserMessage) ? "user" : "assistant";
            msgs.add(Map.of("role", role, "content", m.getText() == null ? "" : m.getText()));
        }
        String summary = loadSummary(sessionId);
        if (summary == null) {
            try {
                Long sid = findSessionId(sessionId);
                if (sid != null) {
                    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Long.class, sid);
                    if (count != null && count > 40) summary = "已持久化 " + count + " 条，近期按 token 预算进窗，历史落库可追溯";
                }
            } catch (Exception ignored) {}
        }
        return new MemorySnapshot(msgs, summary);
    }
}
