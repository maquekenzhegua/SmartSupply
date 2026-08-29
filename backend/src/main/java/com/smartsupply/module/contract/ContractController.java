package com.smartsupply.module.contract;

import com.smartsupply.agent.rag.DocumentParser;
import com.smartsupply.agent.rag.RagService;
import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final RagService ragService;
    private final DocumentParser parser;

    public ContractController(JdbcTemplate jdbc, ChatClient chatClient, RagService ragService, DocumentParser parser) {
        this.jdbc = jdbc; this.chatClient = chatClient; this.ragService = ragService; this.parser = parser;
    }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.*, s.name as supplier_name FROM contract c LEFT JOIN supplier s ON s.id=c.supplier_id ORDER BY c.id DESC LIMIT ? OFFSET ?",
                size, (page-1)*size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM contract", Long.class);
        return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
    }

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value="supplierId", required=false) Long supplierId) throws Exception {
        String text = parser.parse(file);
        String fileName = file.getOriginalFilename() == null ? "未命名合同" : file.getOriginalFilename();
        if (text.isBlank()) return Result.fail(400, "文件解析为空");
        jdbc.update("INSERT INTO contract(title, supplier_id, file_name, status) VALUES (?,?,?,?)",
                fileName, supplierId, fileName, "REVIEWING");
        Long contractId = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "contract", "title", fileName);
        ragService.ingest(fileName, text, "CONTRACT");
        String context = ragService.recall("合同风控 无限连带责任 违约金 交付时间");
        String prompt = "你是合同风控专家。结合公司风控规范：\n" + context + "\n\n请分析以下合同文本，抽取关键要素并标红风险条款、给出修改建议：\n" + text.substring(0, Math.min(text.length(), 6000));
        String report = chatClient.prompt().system("你是严谨的法务风控Agent，按：风险等级/风险点/修改建议 三段输出。").user(prompt).call().content();
        String level = (report != null && report.contains("高风险")) ? "HIGH" : "MEDIUM";
        jdbc.update("INSERT INTO contract_risk_report(contract_id, risk_level, summary, suggestion) VALUES (?,?,?,?)",
                contractId, level, report, report);
        return Result.ok(Map.of("contractId", contractId, "report", report == null ? "" : report));
    }

    @GetMapping("/{id}/risk-report")
    public Result<Map<String, Object>> riskReport(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM contract_risk_report WHERE contract_id=? ORDER BY id DESC LIMIT 1", id);
        if (rows.isEmpty()) return Result.fail("暂无风控报告");
        return Result.ok(rows.get(0));
    }
}
