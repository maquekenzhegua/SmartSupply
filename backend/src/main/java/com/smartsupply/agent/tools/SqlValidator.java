package com.smartsupply.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * NL2SQL 安全校验：只允许 SELECT，只读业务表，阻断注入与 DML。
 */
@Component
public class SqlValidator {

    public void validate(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        String s = sql.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("select")) throw new IllegalArgumentException("仅允许 SELECT 查询");
        String[] forbidden = {"insert", "update", "delete", "drop", "alter", "truncate", "--", "/*", "xp_", " pg_", "information_schema", "union select"};
        for (String kw : forbidden) {
            if (s.contains(kw)) throw new IllegalArgumentException("SQL 包含禁止关键字: " + kw);
        }
        if (s.contains(";")) {
            int first = s.indexOf(';');
            if (first < s.length() - 1 && s.substring(first + 1).trim().length() > 0)
                throw new IllegalArgumentException("不允许分号后拼接多语句");
        }
        if (!(s.contains("supplier") || s.contains("inventory") || s.contains("purchase") || s.contains("contract") || s.contains("sku") || s.contains("product") || s.contains("warehouse") || s.contains("knowledge_doc"))) {
            throw new IllegalArgumentException("仅允许查询供应链业务表");
        }
        if (s.length() > 1200) throw new IllegalArgumentException("SQL 过长");
    }
}
