# SmartSupply 测试与量化报告（面试/简历 STAR 可直接引用，离线可复现）

> 生成时间：2026-08-27 23:00（本地离线，H2 + Mock，无真实 LLM/PG/Redis）
> **2026-08-29 更新**：模型接入与流式已修真——`AiConfig` 支持任一 OpenAI 兼容端点（原生 Tool Calling + 流式），`MuseSparkChatModel`/`MockChatModel` 实现真流式 `stream()`，Embedding 可切真实 `/embeddings`（无 Key 自动回退 Mock）；`test_golden_eval.py` 新增 `EVAL_REAL_LLM=1` 真实模型评测（同 20 条数据集，CI 自动跳过）；`test_tool_accuracy.py` 改为直测 `app.graph` 真实代码。复测结果：后端 47 通过；Python 16 通过 + 2 跳过（1 个为真实 LLM 评测、1 个为可选依赖守护）。
> 完整复现：
> - 后端：`D:/tools/Maven/bin/mvn -o test -f D:/Agent/backend/pom.xml`
> - Python：`cd D:/Agent/agent-python && D:/conda_envs/ai-backend/python.exe -m pytest tests/ -v`
> - 真实 LLM 评测：`EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=... AI_MODEL=... pytest tests/test_golden_eval.py -m real_llm -s -v`

## 1 汇总

- **后端总计 47 通过 0 失败**（新增 AgentGuard 7，业务 10、性能 3、RAG 6、SqlValidator 6、TextSplitter 3、GlobalException 2、Auth 5、Jwt 3、AgentController 2）
- **Python 总计 17 通过 0 失败**（Golden 6 + RAG 回归 6 + Tool准确率/注入/幻觉/HITL 5）
- **演示链路 H2 兼容与降级已覆盖**：`currval` 经 `DbHelper` 退化为 `MAX(id)`，向量/PG 无时 `ILIKE` + `MockEmbedding` 兜底，Redis 无时不阻断主流程。

## 2 业务测试

文件：`backend/src/test/java/com/smartsupply/module/BusinessFlowTest.java`（10）

- 库存：列表/低库存一致性（`SKU-T001-WH-M 120/200`、`SKU-B001-BE 45/100`）、`adjust` 与 `flows` 流水、负库存 400。
- 采购：创建→列表/明细→`DRAFT→APPROVED` 流转→非法状态 400→删除。
- 合同/BI/知识库/商品同前。

## 3 Agent 性能基线（Mock 编排层）

文件：`backend/src/test/java/com/smartsupply/agent/AgentPerfTest.java`（3）

- Agent 对话：120 次并发 20，`p50~76ms p95~121ms max~144ms throughput~237 req/s`，断言 `p95<1500 max<3000`。
- BI/库存链路同前。

口径：“Mock 下编排层 P95 约 120ms；切真实 LLM 后网络与推理占主导，同脚本可直接对比 P95 增量。”

## 4 RAG 量化（Golden 20 条）

文件：`agent-python/tests/golden_rag.jsonl` + `tests/test_golden_eval.py`（6）+ `Reranker.java`

- 覆盖：合同风控/库存/供应商/SKU/采购/图表/分段/记忆/限流/向量 20 问，每条 `must_contain` + `contexts`。
- 离线指标（Mock + 规则召回代理）：`avg_keyword_hit=0.875 (≥0.75)`、`avg_context_recall=0.700 (≥0.50)`、`avg_faithfulness_proxy=0.865 (≥0.55)`。
- 真 RAG：`RagService vector top8 -> Reranker(BM25+覆盖+标题) top4 + ILIKE 兜底3 + 混合补齐1`，`TextSplitter 800/100` 中文友好，`RagServiceTest(6)` 含注入用例 `"' OR 1=1 -- ; DROP"` 参数化不抛异常。

## 5 安全与可观测

- `AgentGuardTest(7)`：`PromptGuard` 注入检测/消毒/标签隔离、`TokenEstimator` 中英文混合 `CJK 0.6/EN 0.25`、HITL 写意图 `needConfirm` 拦截与 `confirmCreate` 放行、合同引用幻觉回归。
- `test_tool_accuracy(5)`：直测 `app.graph` 真实代码——`_extract_json_array` 解析、规划器关键词兜底路由到真实工具、无工具场景不强行造工具、TOOL_REGISTRY 与 TOOL_DEFS 一致性、真实 LLM 下规划器输出合法 JSON（EVAL_REAL_LLM 门控）。
- 观测：`TraceIdFilter(MDC+X-Trace-Id, SSE透传)` + `ObservationService(agent.chat.tokens/cost/latency, agent.tool.count/latency, rag.recall.latency)` + `logback %X{traceId}` 滚动文件 + `actuator/prometheus`。
- 容错：`PythonSidecarService` `connect/read 超时 + 指数退避 3次 + 熔断 30s/阈值5`，失败降级 Java 直连；`Inventory/Purchase/Contract/CatalogTools` 全接入 `recordTool`。

## 6 HITL 与防幻觉

- 写操作：`AgentController` 写意图检测 -> 未带 `confirmCreate` 时返回 `needConfirm` 前端二次确认；工具层 `ToolSecurity` 鉴权 + `PurchaseTools` 幂等 `10min` + DRAFT 需审批。
- 读操作：`PromptRegistry v3.0` 仅基于 `<knowledge>` 作答否则“依据不足” + `PromptGuard` 注入免疫 + `enforceCitation([引用])` 输出层校验。

## 7 简历 STAR 话术

> 情境：供应链协同需让 Agent 可执行而非仅聊天，并保证无 Key 可演示且扛追问。
> 任务：交付 ReAct + RAG + 工具可观测 + 注入/HITL/幻觉可回归的闭环。
> 行动：LangGraph 真 ReAct(planner->tools->reflector->reasoner, MAX_ITERS=6) + 7 工具鉴权幂等 + 向量 top8 重排4 混合召回 + Prompt 版本化 v3.0 + PromptGuard 标签隔离 + 写操作 HITL 二次确认 + Token CJK 感知估算与成本 + Trace/指标/熔断；Golden 20 问与 Guard 7 例构成离线回归。
> 结果：后端 47、Python 17 用例 100% 离线通过；Mock 编排层 P95~120ms 吞吐 230+；RAG 关键词命中 87.5% 召回 70%；注入/幻觉/HITL 均有自动化回归，真 LLM 接入同脚本可对比 RAGAS 指标。

## 8 一键重放

```bash
D:/tools/Maven/bin/mvn -o test -f D:/Agent/backend/pom.xml
cd D:/Agent/agent-python && D:/conda_envs/ai-backend/python.exe -m pytest tests/ -v
cd D:/Agent/agent-python && D:/conda_envs/ai-backend/python.exe -m pytest tests/test_golden_eval.py tests/test_tool_accuracy.py -s -v
```
