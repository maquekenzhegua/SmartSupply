package com.smartsupply.common;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PG/H2 兼容的自增 ID 获取：生产用 pg_get_serial_sequence，H2 离线演示退化为 MAX(id)。
 * 避免 H2 下 SELECT currval(...) 500，是业务测试可回归的前提。
 */
public final class DbHelper {
    private DbHelper() {}

    public static Long lastInsertId(JdbcTemplate jdbc, String table) {
        try {
            return jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('" + table + "','id'))", Long.class);
        } catch (Exception e) {
            return jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
        }
    }

    public static Long lastInsertIdByUnique(JdbcTemplate jdbc, String table, String uniqueCol, Object uniqueVal) {
        try {
            return jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('" + table + "','id'))", Long.class);
        } catch (Exception e) {
            return jdbc.queryForObject("SELECT MAX(id) FROM " + table + " WHERE " + uniqueCol + "=?", Long.class, uniqueVal);
        }
    }
}
