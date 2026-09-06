# 安全护栏规范（PromptGuard 与 SqlValidator）

PromptGuard：
- 检测并过滤 "Ignore previous instructions"、"忽略之前指令" 等注入话术。
- 过滤 "System:" 前缀注入与角色劫持（如 DAN 越狱）。
- wrapUserContent 将用户输入隔离在 <user_query> 标签内，并注入 [约束] 仅基于 <knowledge> 作答，防止伪造 <knowledge> 标签提权。
- 敏感数据防外泄：供应商联系方式等字段不进入工具返回，拒绝批量导出请求。

SqlValidator（BI NL2SQL 链路）：
- 仅允许 SELECT 语句，拦截 DROP、DELETE、UPDATE 等危险操作。
- 关键词检索采用参数化 ILIKE，防 SQL 注入。

对话历史保护：会话按用户隔离，仅归属者可访问，跨用户读取历史会被拒绝。
