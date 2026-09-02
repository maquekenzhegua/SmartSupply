# Agent 观测 / 评测 / 治理

## 观测
- 异步落库：ObservationService 计数器 + 线程池(2,4,60s,1000) 写入 agent_run/step/tool_call，失败仅告警
- 跨语言 trace：TraceIdFilter 生成 X-Trace-Id，经 TraceContext ThreadLocal + MDC 透传至 PythonSidecar，回传 trace/tool_results/iters 落 agent_step
- Token 真实化：MuseSparkChatModel 用 ThreadLocal TokenContext 避免并发串号；TokenEstimator 接 jtokkit cl100k_base，成本按可配置单价
- 指标：agent.chat.latency/tokens/cost、ttft、tool.count/latency、rag.rerank.count，可经 /actuator/prometheus 抓取

## 评测
- 数据集：golden_rag.jsonl 60 条（RAG 20 + 工具 10 + HITL 10 + 注入 10 + 扩展 10），1024 维一致
- 离线门禁：test_golden_eval + test_agent_eval，mock avg_keyword_hit 0.90
- LLM-as-judge：scripts/llm_judge.py，faithfulness/relevance 0-2，产出 docs/eval-report-*.md，CI mock 门禁阻断

## 治理
- Runs：按用户/会话/mode/时间筛选，详情含步骤时间线与工具输入输出
- Costs：按天/类型/用户聚合 ECharts
- Prompts：prompt_version 表，激活版 + 回滚
- Eval：反馈统计 + 报告索引
- 权限：/api/admin/** 需 ADMIN，@EnableMethodSecurity

## 数据模型
agent_run(trace_id, user, session, agent_type, mode, latency, tokens, cost, token_source) -> agent_step(seq, node, digest) -> agent_tool_call(tool, args, result_digest)
user_feedback(rating, comment) 驱动评测候选
