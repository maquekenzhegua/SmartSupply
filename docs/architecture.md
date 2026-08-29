# SmartSupply 架构说明（C路）

## 目标

用企业级底座的工程质量，承载原创供应链 Agent 的业务价值，满足 Agent 开发岗对“全栈 + RAG + Tool Calling + 安全 + 可演示”的全方位考察。

## 分层

- 接入层：Vue3 + Vite + Element Plus + Pinia + Vue-ECharts；全局 Ask Agent 搜框 + Agent 工作台 + 各业务页的 AI 入口
- 网关与鉴权：Spring Security + JWT（`JwtAuthFilter`），CORS 放行，`/api/auth/**` 免鉴权，`actuator/prometheus` 需鉴权
- 业务域：supplier / product / sku / inventory / purchase / contract / bi，统一 `JdbcTemplate` + 参数化 ILIKE，`SqlValidator` 白名单
- Agent 层：`ChatClient` + `@Tool(7)` + `VectorStore(HNSW)` + `RagService(向量8->双轨重排4 auto:cross-encoder->BM25回退+关键词兜底)` + `PromptRegistry(版本化)` + `PromptGuard(注入消毒+标签隔离)` + `ReplenishmentScheduler`
- 重排：`Reranker(BM25离线)` + `CrossEncoderReranker(调 Python /api/rag/rerank，熔断回退)` + `RAG_RERANK_MODE=auto|bm25|cross-encoder`
- 深度推理：`agent-python` LangGraph `planner->tools->reflector->reasoner` 真 ReAct，`MAX_ITERS=6`，trace 可审计
- 基础设施：PostgreSQL + pgvector、Redis、MinIO，Docker Compose 一键起

## 关键序列

### 合同风控（RAG + 引用 + 防幻觉）
上传 -> Tika 解析 -> `TextSplitter 800/100` -> `knowledge_doc` + `knowledge_chunk(embedding 1536)` -> `PgVectorStore.similaritySearch(topK=8)->CrossEncoderReranker(auto: Python cross-encoder->BM25回退) top4 + rag.rerank.count 指标` + `ILIKE 兜底3` -> `PromptGuard.wrap(<knowledge>+<user_query>)` -> `PromptRegistry v3.0(仅基于召回+引用)` -> `ChatClient -- usage回填actual/estimated + TTFT` -> `enforceCitation([引用])` -> 写 `contract_risk_report`

切面：`TraceIdFilter` 全链路 traceId，`ObservationService` 记录 `agent.chat.tokens/cost/latency(source=actual|estimated)`、`agent.chat.ttft(首字)`、`rag.recall.latency`、`rag.rerank.count(mode)`、`agent.tool.count/latency`，`logback` 带 `%X{traceId}` 滚动文件；`GET /api/agent/metrics/summary` 聚合可视化，SSE 的 `done` 事件回传 `{ttfbMs,totalMs,tokenSource}`。

### 补货预测（ReAct + HITL）
用户点“AI建议”或定时任务 -> `AgentController` 检测写意图 -> 若 `confirmCreate!=true` 返回 `needConfirm` 前端二次确认 -> `ChatClient` 携带 `InventoryTools/PurchaseTools/CatalogTools/ContractTools` -> LLM 多轮 `planner->tools->reflector` 自主选择 `listLowStock/getInventory/listSuppliers/createPurchaseOrder` -> `ToolSecurity` 鉴权 + `IdempotencyService(10min)` + 入参校验 + DRAFT 默认需审批 -> 前端展示建议 + 采购单表新增 DRAFT

Python 深度推理：前端 `useDeep=true` 且边车健康时 `PythonSidecarService` 经 `RestClient(超时+指数退避3次+熔断30s)` 调 `POST /api/reason`，LangGraph 在 Python 侧做规划与工具调度，失败降级 Java 直连。

### NL2SQL（只读 + 校验）
用户问题 -> `PromptRegistry v1.3(只读)` -> LLM 生成 SELECT -> `PromptGuard` 标签隔离 -> `SqlValidator(禁 DML/* union pg_ 等 + 多语句 + 表白名单)` -> `JdbcTemplate.queryForList` 只读执行 -> 转 ECharts，前端 `vue-echarts` 渲染。

## 配置切换

- 无 Key：`smartsupply.ai.mock=true` 走 `MockChatModel` + `MockEmbeddingModel`，全链路可演示，Token 按 `TokenEstimator(CJK 0.6/EN 0.25)` 估算并计费
- 有 Key：设 `OPENAI_API_KEY` 与 `OPENAI_BASE_URL`/`AI_MODEL`，自动走真实模型，`MuseSparkChatModel` 解析 `usage.input_tokens/output_tokens` 回填 `actual` 成本并经 `ObservationService` 落指标（无 usage 则 `estimated`），支持 DeepSeek/通义千问/Ollama（OpenAI 兼容接口）；`MockChatModel` 同样回填 `estimated` 供离线对账

## 记忆

`ChatMemoryService`：Redis `agent:memory:{sessionId}` 7天/最多40条，进窗20条；超限压缩最早20条为 `agent:memory:summary:{sessionId}`；每次同步写 `chat_session/chat_message` 落库；Redis miss 时从 DB 恢复回写。`GET /api/agent/memory/{sessionId}` 可查 `snapshot{size, summary, messages}`。

## 可观测

- 链路：`TraceIdFilter` 生成 `X-Trace-Id` 入 MDC，响应头回传，SSE 子线程透传
- 指标：`ObservationService` -> Micrometer `agent.chat.latency/tokens{source}/cost{source}`、`agent.chat.ttft`、`agent.tool.count/latency`、`rag.recall.latency`、`rag.rerank.count{mode}`，`management.endpoints=health,metrics,prometheus` + `GET /api/agent/metrics/summary`
- 日志：`logback-spring.xml` `%d [%thread] %-5level [%X{traceId}] %logger - %msg` 控制台+滚动文件 `20MB*14天`

## 扩展

- MCP：引入 `spring-ai-starter-mcp-server`，将 `@Tool` 暴露为 MCP Tool
- 评估：`agent-python/tests/golden_rag.jsonl` 20 条 + `test_golden_eval.py(6)` 离线规则指标，`test_tool_accuracy.py(5)` 工具选型/注入/幻觉/HITL 回归，`AgentGuardTest(7)` 后端注入/HITL/引用/Token 回归
