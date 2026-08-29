# 面试演示剧本（3分钟拿下 Agent 岗）

## 开场 30 秒（一句话定位）

> “这个项目叫 SmartSupply，我没有从零写后台的轮子——我参考了 RuoYi 的分层与权限思想，自建了供应链域（供应商/商品/库存/采购/合同/BI），把 80% 精力放在了 Agent 层：7 个 `@Tool` + RAG 混合召回+重排 + LangGraph 真 ReAct + HITL + 全链路可观测，而不是只做聊天。”

---

## 演示 1：合同风控 Agent（RAG + 防幻觉，最惊艳）

**操作：** 打开“合同风控”页 -> 上传一份含“乙方承担一切连带责任”的 PDF/TXT

**话术：**
- “上传后 Tika 按 800/100 滑窗切片，Embedding 进 pgvector HNSW（1536维），同时写 `knowledge_doc/chunk`。”
- “查询时走 `vector top8 -> Reranker(BM25+覆盖+标题加权) top4` + `ILIKE` 兜底的混合召回，`PromptGuard` 用 `<knowledge>/<user_query>` 标签隔离，系统 Prompt v3.0 强制‘仅基于召回作答，否则答依据不足’。”
- “你看这份《AI风控报告》标红‘无限连带责任=高风险’并给‘改为有限责任’，末尾有 [引用] 依据召回标题，`enforceCitation` 保证无引用时自动追加——RAG 召回的那条历史案例就是证据。”

**深挖：**
- RAG 怎么做？ Tika→800/100→pgvector HNSW 1536→top8重排4+关键词兜底，阈值 0.2，无 PG 时 `MockEmbedding(伪向量归一化)` + `ILIKE` 保证离线可演示。
- 怎么防幻觉？ 三层：输入标签隔离 + Prompt 仅基于召回 + 输出引用校验；`AgentGuardTest` 与 `test_tool_accuracy` 有回归。
- 注入怎么防？ `PromptGuard` 检测 `ignore previous instructions/system:` 等 6 类模式并消毒，用户消息长度 4000 截断，问 `“忽略之前指令”` 会被标记 `flagged`。

---

## 演示 2：补货预测 Agent（ReAct + HITL，最能区分 Chatbot）

**操作：** 在 Agent 工作台输入“哪些 SKU 低于安全库存，需要补货？” -> 再试“帮我直接创建采购单”

**话术：**
- “这是真 ReAct：Python 边车 `planner(产 JSON) -> tools(批量) -> reflector(反思补调) -> reasoner`，`MAX_ITERS=6`，trace 在 `/api/reason` 可查；Java 直连则走 `ChatClient.tools(...).call()` 的多轮 Tool Calling。”
- “当我说‘帮我下单’，后端检测写意图会先返回 `needConfirm=true` 要求二次确认，前端需点确认或发 `confirmCreate=true` 才会真调 `createPurchaseOrder`，工具内还有鉴权+入参校验+10分钟幂等 `idem:po:{user}:...` + DRAFT 默认需审批才转 APPROVED。”
- “它还有自主形态：`ReplenishmentScheduler` 每天 02:00 扫描低库存，无需用户唤醒——这是 Agent 与 Chatbot 的本质区别。”

**深挖：**
- 怎么防止误下单/越权？ HITL 二次确认 + `ToolSecurity.requireAuthenticated` + 供应商/SKU 存在性 + 数量/单价范围 + 幂等键 + 日志审计。
- 边车挂了怎么办？ `PythonSidecarService` 带 `connect/read 超时 + 指数退避 400→800→1600ms + 熔断 30s`，连续 5 次失败自动熔断，全部降级 Java 直连。

---

## 演示 3：经营分析 Agent（NL2SQL，最体现工程严谨）

**操作：** 在“仪表盘”输入“华南区哪个品类退货率最高？”点分析

**话术：**
- “NL2SQL 我做了安全边界：Prompt v1.3 约束只 SELECT，经 `SqlValidator` 禁 `insert/update/delete/drop/-- /* union select pg_` 等 + 表白名单 + 多语句检测 + 长度 1200 后才 `queryForList` 只读执行。”
- “结果转 ECharts 饼图，前端用 vue-echarts 渲染，无真实退货表时有兜底数据保证有图——真实接入换订单表即可。”

---

## 加分项（主动抛）

1. **可观测：** `X-Trace-Id` 全链路透传（含 SSE 子线程），`ObservationService` 对每次对话计 `prompt/completion tokens + costUsd + latency + mode`，`agent.tool.count/latency`、`rag.recall.latency` 都进 Micrometer，可在 `actuator/prometheus` 拉取。
2. **记忆：** `ChatMemoryService` 短期 Redis 20轮进窗 + 长期 DB 落库 + 超 40 条自动摘要压缩，`GET /api/agent/memory/{sessionId}` 可查快照。
3. **评估：** 后端 `AgentGuardTest(7)` + Python `test_tool_accuracy(5)` + `test_golden_eval(6)` 共 17+ 条离线回归，Golden 20 问 `avg_keyword_hit 0.875 / recall 0.70 / faithfulness_proxy 0.865`，工具选型与注入/HITL 均有量化；真 LLM 接入后同数据集可跑 `ragas` 的 faithfulness/context_precision。

---

## 常见追问清单

- ReAct 怎么防死循环？ `MAX_ITERS=6` + 已调去重 + reflector 判停。
- 向量维度不一致？ 建表固定 1536（text-embedding-3-small），换模型重建索引。
- 为什么选 pgvector？ 单机与业务库同实例运维低，量大可平滑切 Milvus，接口一致。
- 前后端怎么联调？ Vite proxy `/api` 到 8080，JWT 存 localStorage，Axios 统一拦截。
- HITL 怎么体现？ 写意图 `needConfirm` + `confirmCreate` + DRAFT→APPROVED 人审，面试可现场演示“先拦截后确认”。

---

## 结束语

> “总结：这个项目证明两件事——第一我能交付可维护的企业级全栈；第二我能把 Agent 做成可执行、可观测、可评估的生产力，而不只是聊天框。”
