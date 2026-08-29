package com.smartsupply.agent.tools;

import com.smartsupply.agent.ObservationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ContractTools {

    private final JdbcTemplate jdbc;
    private final ObservationService observation;
    public ContractTools(JdbcTemplate jdbc, ObservationService observation) { this.jdbc = jdbc; this.observation = observation; }

    @Tool(description = "按关键词搜索合同，返回 id/title/status/amount，关键词走参数化 ILIKE")
    public List<Map<String, Object>> searchContracts(
            @ToolParam(description = "关键词，如 服装/采购/2026") String keyword) {
        long start = System.currentTimeMillis();
        try {
            String q = "%" + (keyword == null ? "" : keyword.trim()) + "%";
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, title, status, amount FROM contract WHERE title ILIKE ? ORDER BY id DESC LIMIT 10", q);
            observation.recordTool("searchContracts", true, System.currentTimeMillis() - start);
            return rows;
        } catch (Exception e) {
            observation.recordTool("searchContracts", false, System.currentTimeMillis() - start);
            throw e;
        }
    }

    @Tool(description = "查询某合同的风险报告，只读")
    public Map<String, Object> getContractRisk(
            @ToolParam(description = "合同ID") Long contractId) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            if (contractId == null || contractId <= 0) throw new IllegalArgumentException("contractId 不合法");
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT risk_level, summary, suggestion FROM contract_risk_report WHERE contract_id=? ORDER BY id DESC LIMIT 1", contractId);
            if (rows.isEmpty()) { ok = true; return Map.of("found", false, "msg", "暂无风控报告，先上传合同触发分析"); }
            ok = true;
            return Map.of("found", true, "report", rows.get(0));
        } finally {
            observation.recordTool("getContractRisk", ok, System.currentTimeMillis() - start);
        }
    }
}
