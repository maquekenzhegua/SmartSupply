package com.smartsupply.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 插入并直接取回自增主键：PreparedStatementCreator 显式声明返回列 "id"。
 *
 * 替代此前的 lastInsertIdByUnique（INSERT 后按"唯一列"反查 MAX(id)）——但 contract.title、
 * product.name、warehouse.name、knowledge_doc.title 均无 UNIQUE 约束，并发同名插入时会
 * 取错行、子表挂错父记录；purchase_order.order_no/sku.sku_code 虽确有唯一约束，也依赖
 * PG currval / H2 MAX(id) 两套不同口径。统一改为显式 generated keys：
 * PG 生成 RETURNING id（单列，规避 RETURNING * 多列导致 KeyHolder.getKey() 抛
 * "multiple keys" 的事故，见 ObservationService.insertRun 同款修复），H2 原生支持，双方言一致。
 */
public final class DbHelper {
    private DbHelper() {}

    public static long insertAndReturnId(JdbcTemplate jdbc, String insertSql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(insertSql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) ps.setNull(i + 1, java.sql.Types.NULL);
                else ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number id = kh.getKey();
        if (id == null) throw new IllegalStateException("INSERT 未返回生成主键: " + insertSql);
        return id.longValue();
    }
}
