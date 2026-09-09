package com.smartsupply.admin;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import com.smartsupply.config.PromptRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/agent")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAgentController {

    private final JdbcTemplate jdbc;
    private final PromptRegistry promptRegistry;

    public AdminAgentController(JdbcTemplate jdbc, PromptRegistry promptRegistry) {
        this.jdbc = jdbc;
        this.promptRegistry = promptRegistry;
    }

    @GetMapping("/runs")
    public Result<PageResult<Map<String,Object>>> runs(
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String user,
            @RequestParam(required=false) String sessionId,
            @RequestParam(required=false) String mode,
            @RequestParam(required=false) String from,
            @RequestParam(required=false) String to) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (user != null && !user.isBlank()) { where.append(" AND username=? "); args.add(user); }
        if (sessionId != null && !sessionId.isBlank()) { where.append(" AND session_id=? "); args.add(sessionId); }
        if (mode != null && !mode.isBlank()) { where.append(" AND mode=? "); args.add(mode); }
        if (from != null && !from.isBlank()) { where.append(" AND created_at >= ?::timestamptz "); args.add(from); }
        if (to != null && !to.isBlank()) { where.append(" AND created_at <= ?::timestamptz "); args.add(to); }
        String countSql = "SELECT COUNT(*) FROM agent_run" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        String sql = "SELECT * FROM agent_run " + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> qArgs = new ArrayList<>(args); qArgs.add(size); qArgs.add((page-1)*size);
        List<Map<String,Object>> rows = jdbc.queryForList(sql, qArgs.toArray());
        return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
    }

    @GetMapping("/runs/{id}")
    public Result<Map<String,Object>> runDetail(@PathVariable long id) {
        List<Map<String,Object>> runs = jdbc.queryForList("SELECT * FROM agent_run WHERE id=?", id);
        if (runs.isEmpty()) return Result.fail(404, "run not found");
        Map<String,Object> run = runs.get(0);
        List<Map<String,Object>> steps = jdbc.queryForList("SELECT * FROM agent_step WHERE run_id=? ORDER BY seq", id);
        List<Map<String,Object>> tools = jdbc.queryForList("SELECT * FROM agent_tool_call WHERE run_id=? ORDER BY id", id);
        Map<String,Object> out = new HashMap<>(run);
        out.put("steps", steps);
        out.put("toolCalls", tools);
        return Result.ok(out);
    }

    @GetMapping("/costs")
    public Result<Map<String,Object>> costs(
            @RequestParam(defaultValue="day") String groupBy,
            @RequestParam(required=false) String from,
            @RequestParam(required=false) String to) {
        String groupExpr;
        if ("agentType".equals(groupBy)) groupExpr = "agent_type";
        else if ("user".equals(groupBy)) groupExpr = "username";
        else groupExpr = "DATE(created_at)";
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (from != null && !from.isBlank()) { where.append(" AND created_at >= ?::timestamptz "); args.add(from); }
        if (to != null && !to.isBlank()) { where.append(" AND created_at <= ?::timestamptz "); args.add(to); }
        String sql = "SELECT " + groupExpr + " as grp, COUNT(*) as cnt, SUM(total_tokens) as tokens, SUM(cost_usd) as cost FROM agent_run " + where + " GROUP BY grp ORDER BY grp";
        List<Map<String,Object>> rows = jdbc.queryForList(sql, args.toArray());
        return Result.ok(Map.of("groupBy", groupBy, "rows", rows));
    }

    @GetMapping("/prompts")
    public Result<List<Map<String,Object>>> prompts() {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM prompt_version ORDER BY agent_type, id DESC");
        if (rows.isEmpty()) {
            // fallback to registry via reflection-less: return empty
        }
        return Result.ok(rows);
    }

    @PostMapping("/prompts")
    public Result<Map<String,Object>> createPrompt(@RequestBody Map<String,String> body) {
        String agentType = body.getOrDefault("agentType","general");
        String content = body.get("content");
        if (content == null || content.isBlank()) return Result.fail(400, "content 不能为空");
        String version = body.get("version");
        String user = com.smartsupply.common.CurrentUser.username();
        // 经 PromptRegistry 发布：DB 落库 + 缓存失效，下一次对话立即生效（此前激活只改表、运行时不读，激活即摆设）
        PromptRegistry.PromptVersion pv = promptRegistry.publish(agentType, version, content, user);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM prompt_version WHERE agent_type=?", Long.class, agentType);
        return Result.ok(Map.of("id", id==null?0:id, "version", pv.version()));
    }

    @PostMapping("/prompts/{id}/activate")
    public Result<Void> activate(@PathVariable long id) {
        try {
            promptRegistry.activate(id);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(404, e.getMessage());
        }
    }

    /** 生效提示词视图：每个 agent_type 当前真正注入对话的版本与来源（db=治理表生效 / builtin=内置默认），验证闭环用。 */
    @GetMapping("/prompts/effective")
    public Result<Map<String, Map<String, String>>> promptsEffective() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        promptRegistry.effectiveAll().forEach((type, pv) -> out.put(type, Map.of(
                "version", pv.version(), "source", pv.source())));
        return Result.ok(out);
    }

    @GetMapping("/eval")
    public Result<Map<String,Object>> eval() {
        // read eval reports from docs folder listing via DB fallback: just return feedback stats
        List<Map<String,Object>> feedback = jdbc.queryForList("SELECT rating, COUNT(*) as cnt FROM user_feedback GROUP BY rating");
        Long totalRuns = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Long.class);
        return Result.ok(Map.of("feedback", feedback, "totalRuns", totalRuns==null?0:totalRuns));
    }

    // ---- 在线评测闭环：低分反馈 → 评测候选 → 人工标注后进回归集 ----

    /** 评测候选：负反馈（rating<=maxRating，默认踩=-1）关联运行台账与提问原文。
     *  在线反馈此前只有 GROUP BY 计数展示，"驱动评测候选"只存在于文档——这里是闭环的第一环。 */
    @GetMapping("/eval/candidates")
    public Result<List<Map<String,Object>>> evalCandidates(
            @RequestParam(defaultValue = "-1") int maxRating,
            @RequestParam(defaultValue = "50") int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT f.id AS \"id\", f.rating AS \"rating\", f.comment AS \"comment\", f.run_id AS \"runId\", " +
                "       f.session_id AS \"sessionId\", f.message_id AS \"messageId\", f.created_at AS \"createdAt\", " +
                "       r.agent_type AS \"agentType\", r.mode AS \"mode\", r.model AS \"model\", r.prompt_version AS \"promptVersion\" " +
                "FROM user_feedback f LEFT JOIN agent_run r ON r.id=f.run_id " +
                "WHERE f.rating <= ? ORDER BY f.id DESC LIMIT ?", maxRating, cap);
        for (Map<String,Object> row : rows) enrichWithConversation(row);
        return Result.ok(rows);
    }

    /** 候选导出（golden JSONL，NDJSON）：人工补 ground_truth 后并入 golden_rag.jsonl 即完成回流。
     *  ground_truth 留空——机器绝不代填"正确答案"，这是评测集真实性的底线。 */
    @GetMapping("/eval/candidates/export")
    public org.springframework.http.ResponseEntity<String> exportCandidates(
            @RequestParam(defaultValue = "-1") int maxRating,
            @RequestParam(defaultValue = "100") int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT f.rating AS \"rating\", f.comment AS \"comment\", f.message_id AS \"messageId\" " +
                "FROM user_feedback f WHERE f.rating <= ? ORDER BY f.id DESC LIMIT ?", maxRating, cap);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder ndjson = new StringBuilder();
        for (Map<String,Object> row : rows) {
            enrichWithConversation(row);
            Map<String,Object> line = new LinkedHashMap<>();
            line.put("question", row.getOrDefault("question", ""));
            line.put("ground_truth", "");
            line.put("contexts", List.of());
            line.put("layer", "online");
            line.put("feedback_rating", row.get("rating"));
            line.put("comment", row.get("comment"));
            try {
                ndjson.append(om.writeValueAsString(line)).append("\n");
            } catch (Exception ignored) {}
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=eval-candidates.jsonl")
                .body(ndjson.toString());
    }

    /** 补齐候选的对话原文：被评分消息 + 同会话中它之前最近一条用户提问。 */
    private void enrichWithConversation(Map<String,Object> row) {
        Object mid = row.get("messageId");
        if (!(mid instanceof Number n)) return;
        try {
            List<Map<String,Object>> rated = jdbc.queryForList(
                    "SELECT session_id AS \"sid\", role AS \"role\", content AS \"content\" FROM chat_message WHERE id=?", n.longValue());
            if (rated.isEmpty()) return;
            Object sid = rated.get(0).get("sid");
            row.put("ratedRole", rated.get(0).get("role"));
            row.put("ratedContent", rated.get(0).get("content"));
            if (sid == null) return;
            List<Map<String,Object>> q = jdbc.queryForList(
                    "SELECT content AS \"content\" FROM chat_message WHERE session_id=? AND role='user' AND id<=? ORDER BY id DESC LIMIT 1",
                    ((Number) sid).longValue(), n.longValue());
            if (!q.isEmpty()) row.put("question", q.get(0).get("content"));
        } catch (Exception ignored) {}
    }

    // ---- 评测快照：离线指标随时间留痕（趋势半边） ----

    @PostMapping("/eval/snapshots")
    public Result<Map<String,Object>> addSnapshot(@RequestBody Map<String,Object> body) {
        String source = String.valueOf(body.getOrDefault("source", "manual"));
        String reportFile = body.get("reportFile") == null ? null : String.valueOf(body.get("reportFile"));
        Object metrics = body.get("metrics");
        String metricsJson;
        try {
            metricsJson = metrics == null ? "{}" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metrics);
        } catch (Exception e) { metricsJson = "{}"; }
        String user = com.smartsupply.common.CurrentUser.username();
        jdbc.update("INSERT INTO eval_snapshot(source, report_file, metrics, created_by) VALUES (?,?,?,?)",
                source, reportFile, metricsJson, user);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM eval_snapshot", Long.class);
        return Result.ok(Map.of("id", id == null ? 0 : id));
    }

    @GetMapping("/eval/snapshots")
    public Result<List<Map<String,Object>>> snapshots(@RequestParam(defaultValue = "50") int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT id AS \"id\", source AS \"source\", report_file AS \"reportFile\", metrics AS \"metrics\", " +
                "       created_by AS \"createdBy\", created_at AS \"createdAt\" " +
                "FROM eval_snapshot ORDER BY id DESC LIMIT ?", cap);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Map<String,Object> row : rows) {
            Object m = row.get("metrics");
            if (m instanceof String s && !s.isBlank()) {
                try { row.put("metrics", om.readValue(s, Map.class)); } catch (Exception ignored) {}
            }
        }
        return Result.ok(rows);
    }
}
