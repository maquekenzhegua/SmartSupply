package com.smartsupply.agent;

import org.springframework.stereotype.Component;

/**
 * agent_type 服务端裁决：前端传什么就是什么 → 升级为"客户端值 + 服务端归类"双轨。
 *
 * - 客户端传已知类型（general/contract/replenishment/bi）→ 采信（source=client）；
 * - 客户端传 auto / 空白 / 未知值 → 关键词归类（source=classified），归类结果进 run 台账与响应；
 * - deep 是运行模式不是人设：personas 按消息内容归类（深度模式的系统提示词与成本分组随之真实化）。
 *
 * 归类是启发式（关键词规则），故意不引入 LLM 分类器：分类错误可从台账审计，
 * 且分类本身不该花一次模型调用的钱与延迟。LLM 分类是后续演进项，不是现状。
 */
@Component
public class AgentTypeResolver {

    public record Resolved(String agentType, String source) {}

    private static final String[] KNOWN = {"general", "contract", "replenishment", "bi"};

    public Resolved resolve(String clientType, String message) {
        String ct = clientType == null ? "" : clientType.trim().toLowerCase();
        for (String k : KNOWN) {
            if (k.equals(ct)) return new Resolved(k, "client");
        }
        return new Resolved(classify(message), "classified");
    }

    public String classify(String message) {
        String m = message == null ? "" : message;
        if (m.contains("合同") || m.contains("风控") || m.contains("条款") || m.contains("违约")) return "contract";
        if (m.contains("补货") || m.contains("库存") || m.contains("采购") || m.contains("安全库存") || m.contains("下单")) return "replenishment";
        if (m.contains("报表") || m.contains("统计") || m.contains("趋势") || m.contains("经营") || m.contains("环比") || m.contains("同比")) return "bi";
        return "general";
    }
}
