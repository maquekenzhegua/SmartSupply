package com.smartsupply.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BI 只读 SQL 的第一道静态校验（结构检查 + 整词黑名单 + 表白名单）。
 *
 * 字符串校验本身挡不住全部绕过（编码、表达式、存储过程变体），所以它只承担"快速失败"：
 * 真正有约束力的执行控制在 {@link com.smartsupply.module.bi.ReadOnlyQueryGateway}——
 * 只读连接（数据库层拒绝写）+ 语句超时 + 最大行数。两层缺一不可，构成纵深防御。
 *
 * 本层修复过的绕过：
 *  - "union select" 只挡连写形式 → 改为词边界整词匹配，"union\nselect"、"union all select" 同样拦截；
 *  - " pg_" 需前导空格才命中 → 危险函数改为调用形态匹配，"pg_sleep(3)" 任意位置拦截；
 *  - 子查询读内部表（如 (SELECT password_hash FROM sys_user)）→ 内部表显式禁查（词边界）。
 */
@Component
public class SqlValidator {

    /** DML/DDL/特权语句与信息泄露面，按词边界整词匹配 */
    private static final Pattern FORBIDDEN_WORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|copy|call|do|"
            + "vacuum|reindex|listen|notify|union|outfile|load_file|benchmark|extractvalue|"
            + "information_schema|pg_catalog|pg_tables|pg_class|dblink)\\b");

    /** 危险内建/扩展函数（匹配调用形态） */
    private static final Pattern FORBIDDEN_CALLS = Pattern.compile(
            "\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|"
            + "sleep|benchmark|current_user|session_user|version)\\s*\\(");

    /** 内部表：账号凭据、会话消息、观测台账、基础设施表——对 BI 分析无意义且含敏感数据 */
    private static final Pattern FORBIDDEN_TABLES = Pattern.compile(
            "\\b(sys_user|chat_session|chat_message|agent_run|agent_step|agent_tool_call|"
            + "flyway_schema_history|vector_store)\\b");

    public void validate(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        String s = sql.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("select")) throw new IllegalArgumentException("仅允许 SELECT 查询");
        if (s.length() > 1200) throw new IllegalArgumentException("SQL 过长");
        // 多语句：分号只允许出现在结尾
        int semi = s.indexOf(';');
        if (semi >= 0 && s.substring(semi + 1).trim().length() > 0) {
            throw new IllegalArgumentException("不允许分号后拼接多语句");
        }
        // 注释是经典绕过载体（截断/吞掉校验片段），一律拒绝
        if (s.contains("--") || s.contains("/*") || s.contains("*/")) {
            throw new IllegalArgumentException("不允许 SQL 注释");
        }
        Matcher m = FORBIDDEN_WORDS.matcher(s);
        if (m.find()) throw new IllegalArgumentException("SQL 包含禁止关键字: " + m.group());
        m = FORBIDDEN_CALLS.matcher(s);
        if (m.find()) throw new IllegalArgumentException("SQL 包含禁止函数: " + m.group());
        m = FORBIDDEN_TABLES.matcher(s);
        if (m.find()) throw new IllegalArgumentException("不允许查询内部表: " + m.group());
        if (!(s.contains("supplier") || s.contains("inventory") || s.contains("purchase") || s.contains("contract")
                || s.contains("sku") || s.contains("product") || s.contains("warehouse") || s.contains("knowledge_doc"))) {
            throw new IllegalArgumentException("仅允许查询供应链业务表");
        }
    }
}
