# 可观测性与 BI 分析

链路追踪：TraceIdFilter 生成 X-Trace-Id，跨 Java 与 Python 边车关联；agent_run 表记录每次运行，支持 trace 回放。

耗时查看：如何查看 Agent 的执行耗时——ObservationService 记录 agent.chat.tokens/cost/latency、agent.tool.count/latency、rag.recall.latency 等指标，延迟直方图与 latency_ms 可通过 /actuator/prometheus 抓取，或在管理后台 Runs 页按 traceId 查看单次耗时。

Token 与成本：token 计数分 actual（模型返回）与 estimated（CJK 0.6/英文 0.25 估算），成本 = tokens × 模型单价，管理后台 Costs 页汇总。

BI 分析：NL 转 SQL 后结果转 ECharts，chartType 为 pie/bar/line 三种之一，前端用 vue-echarts 渲染；生成 SQL 必经 SqlValidator 校验，仅允许 SELECT。

前端展示：AgentChat 工具调用可视化，展示 tools 调用列表与耗时。
