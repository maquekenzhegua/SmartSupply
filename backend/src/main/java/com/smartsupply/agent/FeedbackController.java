package com.smartsupply.agent;

import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/feedback")
public class FeedbackController {
    private final JdbcTemplate jdbc;
    public FeedbackController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping
    public Result<Map<String,Object>> feedback(@RequestBody Map<String,Object> body) {
        Long messageId = body.get("messageId") instanceof Number ? ((Number)body.get("messageId")).longValue() : null;
        Long runId = body.get("runId") instanceof Number ? ((Number)body.get("runId")).longValue() : null;
        String sessionId = (String) body.getOrDefault("sessionId", "");
        Object ratingObj = body.get("rating");
        int rating = 0;
        if (ratingObj instanceof Number) rating = ((Number)ratingObj).intValue();
        else if (ratingObj instanceof String) { try { rating = Integer.parseInt((String)ratingObj); } catch(Exception ignored){} }
        if (rating < -1) rating = -1; if (rating > 1) rating = 1;
        String comment = (String) body.getOrDefault("comment", "");
        jdbc.update("INSERT INTO user_feedback(message_id, run_id, session_id, rating, comment) VALUES (?,?,?,?,?)",
                messageId, runId, sessionId, rating, comment);
        return Result.ok(Map.of("ok", true));
    }
}
