package com.smartsupply.agent.tools;

import com.smartsupply.common.DbHelper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购单持久化：订单头 + 明细两个 INSERT 必须同事务。
 * 此前无事务，头插入成功而明细失败会留下无明细的孤儿采购单（DRAFT 单进入审批流后才发现缺行）。
 * 独立成 Bean 而非 PurchaseTools 内加 @Transactional：避免自调用绕过代理的坑。
 * created_by 列是 BIGINT（sys_user.id），这里把 actor 用户名解析成 id——实跑演练发现
 * 直接塞 username 在 PG 上必然类型报错，而测试 H2 里该路径此前从未真实插库，故未暴露。
 */
@Component
public class PurchaseOrderWriter {

    private final JdbcTemplate jdbc;

    public PurchaseOrderWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Long persist(String orderNo, Long supplierId, double amount, String remark, String actor,
                        Long skuId, int quantity, double unitPrice) {
        Long creatorId;
        try {
            creatorId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, actor);
        } catch (EmptyResultDataAccessException e) {
            creatorId = null; // 操作人用户名进 remark 留审计，id 缺失不阻断业务写入
        }
        Long orderId = DbHelper.insertAndReturnId(jdbc,
                "INSERT INTO purchase_order(order_no, supplier_id, status, total_amount, remark, created_by) VALUES (?,?,?,?,?,?)",
                orderNo, supplierId, "DRAFT", amount, remark, creatorId);
        jdbc.update("INSERT INTO purchase_order_item(order_id, sku_id, quantity, unit_price, amount) VALUES (?,?,?,?,?)",
                orderId, skuId, quantity, unitPrice, amount);
        return orderId;
    }
}
