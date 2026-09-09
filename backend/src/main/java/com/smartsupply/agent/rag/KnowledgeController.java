package com.smartsupply.agent.rag;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final RagService ragService;
    private final DocumentParser parser;
    private final JdbcTemplate jdbc;

    public KnowledgeController(RagService ragService, DocumentParser parser, JdbcTemplate jdbc) {
        this.ragService = ragService; this.parser = parser; this.jdbc = jdbc;
    }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, source_type, created_at, LENGTH(content) as content_len FROM knowledge_doc ORDER BY id DESC LIMIT ? OFFSET ?",
                size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_doc", Long.class);
        return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
    }

    // 知识库写路径统一 ADMIN（上传/录入/删除）；recall 为只读保持登录即可
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/upload")
    @RateLimit(permitsPerMinute = 20, key = "knowledge-upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String title = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String text = parser.parse(file);
        if (text.isBlank()) return Result.fail(400, "文件内容为空或无法解析");
        Long docId = ragService.ingest(title, text, "KNOWLEDGE");
        return Result.ok(Map.of("docId", docId, "title", title, "chunks", TextSplitter.split(text).size()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/text")
    public Result<Map<String, Object>> ingestText(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "手动录入");
        String content = body.getOrDefault("content", "");
        if (content.isBlank()) return Result.fail(400, "内容不能为空");
        Long docId = ragService.ingest(title, content, "MANUAL");
        return Result.ok(Map.of("docId", docId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        jdbc.update("DELETE FROM knowledge_doc WHERE id=?", id);
        return Result.ok();
    }

    @PostMapping("/recall")
    public Result<Map<String, Object>> recall(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) return Result.fail(400, "query 不能为空");
        RagService.RecallDetail d = ragService.recallWithDetail(query);
        var citList = d.citations()==null? java.util.List.of() : d.citations().stream().map(c -> (Object)Map.of("docId", c.docId(), "title", c.title(), "snippet", c.snippet(), "score", c.score())).toList();
        return Result.ok(Map.of("query", query, "context", d.context(), "citations", citList, "vectorHits", d.vectorHits(), "reranked", d.reranked(), "latencyMs", d.latencyMs()));
    }

    @PostMapping("/recall/batch")
    public Result<Map<String, Object>> recallBatch(@RequestBody Map<String, Object> body) {
        Object qs = body.get("queries");
        if (!(qs instanceof List)) return Result.fail(400, "queries 需为数组");
        List<String> queries = ((List<?>) qs).stream().map(String::valueOf).toList();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (String q : queries) {
            RagService.RecallDetail d = ragService.recallWithDetail(q);
            results.add(Map.of("query", q, "context", d.context(), "latencyMs", d.latencyMs()));
        }
        return Result.ok(Map.of("results", results));
    }
}
