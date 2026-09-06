package com.smartsupply.module.bi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读查询网关：SqlValidator 静态校验之后的第二道（真正有约束力的）防线。
 * SqlValidator 校验的是"文本"，这里约束的是"执行"：
 *  - Connection.setReadOnly(true)：PG/H2 在会话层切 READ ONLY，任何写语句由数据库直接拒绝，
 *    不再依赖字符串校验的完备性；
 *  - Statement.setQueryTimeout：语句级超时，防重查询/函数调用拖垮连接池；
 *  - Statement.setMaxRows：结果集硬上限，防大结果打爆堆内存。
 * 主数据源即可（只读由会话属性保证），无需为 BI 单独维护只读账号；上生产后可再换独立只读实例。
 */
@Component
public class ReadOnlyQueryGateway {

    private final JdbcTemplate jdbc;
    private final int maxRows;
    private final int timeoutSeconds;

    public ReadOnlyQueryGateway(JdbcTemplate jdbc,
                                @Value("${smartsupply.bi.max-rows:1000}") int maxRows,
                                @Value("${smartsupply.bi.query-timeout-seconds:5}") int timeoutSeconds) {
        this.jdbc = jdbc;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<Map<String, Object>> query(String sql) {
        return jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
            boolean oldReadOnly = con.isReadOnly();
            con.setReadOnly(true);
            try (Statement st = con.createStatement()) {
                st.setQueryTimeout(timeoutSeconds);
                st.setMaxRows(maxRows);
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next() && out.size() < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        out.add(row);
                    }
                    return out;
                }
            } finally {
                try { con.setReadOnly(oldReadOnly); } catch (Exception ignored) { }
            }
        });
    }
}
