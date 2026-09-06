package com.smartsupply.module.purchase;

import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import com.smartsupply.agent.tools.PurchaseTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 写操作的可信执行端点（供 Python LangGraph 边车的 create_purchase_order 工具回环调用）。
 *
 * 纵深防线分工：
 *  - 决策层（Python 图内）：create_purchase_order 执行前 langgraph interrupt() 挂起，
 *    前端弹出批准/拒绝，用户批准后携 Command(resume) 恢复——没人批准，图永远不会走到这里；
 *  - 执行层（本端点）：复用 PurchaseTools.createPurchaseOrder 全套硬约束——
 *    requireSupplierWritePerm 强制 ADMIN 角色（随回环透传的真实用户 JWT 裁决）、
 *    供应商/SKU 存在性校验、幂等键（10 分钟去重）、独立事务落库、DRAFT 状态留人工审批。
 * 边车本身无独立权限：没有合法用户 JWT（或服务账号非 ADMIN），本端点在工具层即拒绝。
 */
@RestController
@RequestMapping("/api/agent/purchase-orders")
public class AgentPurchaseController {

    private final PurchaseTools purchaseTools;

    public AgentPurchaseController(PurchaseTools purchaseTools) {
        this.purchaseTools = purchaseTools;
    }

    @PostMapping
    @RateLimit(permitsPerMinute = 20, key = "agent-po-create")
    public org.springframework.http.ResponseEntity<Result<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        try {
            Long supplierId = Long.valueOf(String.valueOf(body.get("supplierId")));
            String skuCode = String.valueOf(body.get("skuCode"));
            int quantity = Integer.parseInt(String.valueOf(body.get("quantity")));
            double unitPrice = Double.parseDouble(String.valueOf(body.get("unitPrice")));
            return org.springframework.http.ResponseEntity.ok(Result.ok(purchaseTools.createPurchaseOrder(supplierId, skuCode, quantity, unitPrice)));
        } catch (SecurityException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Result.fail(403, e.getMessage() == null ? "无写操作权限" : e.getMessage()));
        } catch (IllegalArgumentException e) {
            // 非 200 状态码：边车错误信封能携带真实原因（信封语义：ok=false + error 文本）
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Result.fail(400, e.getMessage() == null ? "参数校验失败" : e.getMessage()));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError()
                    .body(Result.fail(500, "创建采购单失败: " + e.getMessage()));
        }
    }
}
