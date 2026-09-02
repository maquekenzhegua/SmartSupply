package com.smartsupply.admin;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/agent")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAgentController {

    private final JdbcTemplate jdbc;
    public AdminAgentController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        String version = body.getOrDefault("version", "v" + System.currentTimeMillis());
        String user = com.smartsupply.common.CurrentUser.username();
        // deactivate old
        jdbc.update("UPDATE prompt_version SET active=false WHERE agent_type=?", agentType);
        jdbc.update("INSERT INTO prompt_version(agent_type, version, content, active, created_by) VALUES (?,?,?,?,?)",
                agentType, version, content, true, user);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM prompt_version WHERE agent_type=?", Long.class, agentType);
        return Result.ok(Map.of("id", id==null?0:id, "version", version));
    }

    @PostMapping("/prompts/{id}/activate")
    public Result<Void> activate(@PathVariable long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT agent_type FROM prompt_version WHERE id=?", id);
        if (rows.isEmpty()) return Result.fail(404, "prompt not found");
        String at = String.valueOf(rows.get(0).get("agent_type"));
        jdbc.update("UPDATE prompt_version SET active=false WHERE agent_type=?", at);
        jdbc.update("UPDATE prompt_version SET active=true WHERE id=?", id);
        return Result.ok();
    }

    @GetMapping("/eval")
    public Result<Map<String,Object>> eval() {
        // read eval reports from docs folder listing via DB fallback: just return feedback stats
        List<Map<String,Object>> feedback = jdbc.queryForList("SELECT rating, COUNT(*) as cnt FROM user_feedback GROUP BY rating");
        Long totalRuns = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Long.class);
        return Result.ok(Map.of("feedback", feedback, "totalRuns", totalRuns==null?0:totalRuns));
    }
}
