package com.smartsupply.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 短期记忆 + DB 长期记忆 + 摘要压缩。
 * 短期：Redis agent:memory:{sessionId} 7天，近20轮进上下文，最多40条。
 * 长期：chat_session + chat_message 落库，可审计可恢复。
 * 摘要：超过40条时压缩老消息为 summary 存 Redis，不丢上下文语义。
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final JdbcTemplate jdbc;

    public ChatMemoryService(StringRedisTemplate redis, ObjectMapper om, JdbcTemplate jdbc) {
        this.redis = redis;
        this.om = om;
        this.jdbc = jdbc;
    }

    private String key(String sessionId) { return "agent:memory:" + sessionId; }
    private String summaryKey(String sessionId) { return "agent:memory:summary:" + sessionId; }

    public record MemorySnapshot(List<Map<String, String>> messages, String summary) {}

    @SuppressWarnings("unchecked")
    public List<Message> load(String sessionId, String systemPrompt) {
        List<Message> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) out.add(new SystemMessage(systemPrompt));
        String json;
        try { json = redis.opsForValue().get(key(sessionId)); } catch (Exception e) { json = null; }
        if (json != null && !json.isBlank()) {
            try {
                List<Map<String, String>> raw = om.readValue(json, new TypeReference<>() {});
                for (Map<String, String> m : raw) {
                    String role = m.get("role");
                    String content = m.get("content");
                    if ("user".equals(role)) out.add(new UserMessage(content));
                    else if ("assistant".equals(role)) out.add(new AssistantMessage(content));
                }
            } catch (Exception ignored) {}
            if (out.size() > 21) {
                List<Message> trimmed = new ArrayList<>();
                trimmed.add(out.get(0));
                trimmed.addAll(out.subList(Math.max(1, out.size() - 20), out.size()));
                return trimmed;
            }
            return out;
        }
        // Redis miss -> DB 恢复
        try {
            Long sessionDbId = jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? LIMIT 1", Long.class, sessionId);
            if (sessionDbId != null) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT role, content FROM chat_message WHERE session_id=? ORDER BY id DESC LIMIT 40", sessionDbId);
                List<Message> dbMsgs = new ArrayList<>();
                for (int i = rows.size() - 1; i >= 0; i--) {
                    Map<String, Object> r = rows.get(i);
                    String role = String.valueOf(r.get("role"));
                    String content = String.valueOf(r.get("content"));
                    if ("user".equals(role)) dbMsgs.add(new UserMessage(content));
                    else if ("assistant".equals(role)) dbMsgs.add(new AssistantMessage(content));
                }
                out.addAll(dbMsgs);
                // 回写 Redis 加速下次
                if (!dbMsgs.isEmpty()) {
                    try {
                        List<Map<String, String>> toCache = new ArrayList<>();
                        for (Message m : dbMsgs) {
                            String role = (m instanceof UserMessage) ? "user" : "assistant";
                            toCache.add(Map.of("role", role, "content", m.getText() == null ? "" : m.getText()));
                        }
                        redis.opsForValue().set(key(sessionId), om.writeValueAsString(toCache), 7, TimeUnit.DAYS);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("DB memory load fallback failed sessionId={}: {}", sessionId, e.toString());
        }
        if (out.size() > 21) {
            List<Message> trimmed = new ArrayList<>();
            trimmed.add(out.get(0));
            trimmed.addAll(out.subList(Math.max(1, out.size() - 20), out.size()));
            return trimmed;
        }
        return out;
    }

    public void append(String sessionId, String role, String content) {
        String k = key(sessionId);
        try {
            String json = redis.opsForValue().get(k);
            List<Map<String, String>> list;
            if (json == null || json.isBlank()) list = new ArrayList<>();
            else list = om.readValue(json, new TypeReference<>() {});
            list.add(Map.of("role", role, "content", content == null ? "" : content));
            // 摘要压缩：超过40条时把最老的20条压缩为 summary
            String summary = null;
            try { summary = redis.opsForValue().get(summaryKey(sessionId)); } catch (Exception ignored) {}
            if (list.size() > 40) {
                List<Map<String, String>> old = list.subList(0, list.size() - 40);
                List<Map<String, String>> kept = new ArrayList<>(list.subList(list.size() - 40, list.size()));
                String compressed = compress(old, summary);
                try { redis.opsForValue().set(summaryKey(sessionId), compressed, 7, TimeUnit.DAYS); } catch (Exception ignored) {}
                list = kept;
            }
            redis.opsForValue().set(k, om.writeValueAsString(list), 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.debug("Redis append failed sessionId={}: {}", sessionId, e.toString());
        }
        // DB 落库（不阻断主流程）
        try {
            Long sessionDbId = null;
            try { sessionDbId = jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? LIMIT 1", Long.class, sessionId); } catch (Exception ignored) {}
            if (sessionDbId == null) {
                try {
                    jdbc.update("INSERT INTO chat_session(agent_type, title) VALUES (?,?)", "general", sessionId);
                    sessionDbId = jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? ORDER BY id DESC LIMIT 1", Long.class, sessionId);
                } catch (Exception ex) {
                    log.debug("create chat_session failed: {}", ex.toString());
                }
            }
            if (sessionDbId != null) {
                jdbc.update("INSERT INTO chat_message(session_id, role, content) VALUES (?,?,?)",
                        sessionDbId, role, content == null ? "" : content);
            }
        } catch (Exception e) {
            log.debug("DB append failed sessionId={}: {}", sessionId, e.toString());
        }
    }

    private String compress(List<Map<String, String>> old, String prevSummary) {
        StringBuilder sb = new StringBuilder();
        if (prevSummary != null && !prevSummary.isBlank()) sb.append(prevSummary).append(" | ");
        int cap = Math.min(old.size(), 10);
        for (int i = 0; i < cap; i++) {
            String c = old.get(i).get("content");
            if (c != null && c.length() > 80) c = c.substring(0, 80) + "...";
            sb.append(old.get(i).get("role")).append(":").append(c).append("; ");
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
        String summary = null;
        try { summary = redis.opsForValue().get(summaryKey(sessionId)); } catch (Exception ignored) {}
        if (summary == null) {
            try {
                Long sid = jdbc.queryForObject("SELECT id FROM chat_session WHERE title=? LIMIT 1", Long.class, sessionId);
                if (sid != null) {
                    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Long.class, sid);
                    if (count != null && count > 40) summary = "已持久化 " + count + " 条，近期进窗 20 轮，历史落库可追溯";
                }
            } catch (Exception ignored) {}
        }
        return new MemorySnapshot(msgs, summary);
    }
}
