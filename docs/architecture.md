# SmartSupply 架构说明（C路）

> 源码导读（每个文件读什么、按什么顺序）见 [code-map.md](code-map.md)；实现中踩过的坑见 [lessons-learned.md](lessons-learned.md)。

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
- 观测：agent_run/step/tool_call/user_feedback + prompt_version 落库，TraceId 跨 Java/Python，jTokkit 真实计费
- 治理：/api/admin/agent/{runs,costs,prompts,eval} ADMIN 只读

## 关键序列

### 合同风控（RAG + 引用 + 防幻觉）
上传 -> Tika 解析 -> `TextSplitter 800/100` -> `knowledge_doc` + `knowledge_chunk(embedding 1024)` -> `PgVectorStore.similaritySearch(topK=8)->CrossEncoderReranker(auto: Python cross-encoder->BM25回退) top4 + rag.rerank.count 指标` + `ILIKE 兜底3` -> `PromptGuard.wrap(<knowledge>+<user_query>)` -> `PromptRegistry v3.0(仅基于召回+引用)` -> `ChatClient -- usage回填actual/estimated + TTFT` -> `enforceCitation([引用])` -> 写 `contract_risk_report`

切面：`TraceIdFilter` + `TraceContext` 全链路 `X-Trace-Id`（含 SSE），`ObservationService` 计数器+异步落库 `agent_run/step/tool_call`，`TokenContext` 隔离并发，jTokkit `cl100k_base` 真实分词；`GET /api/agent/metrics/summary` + `actuator/prometheus`。

### 补货预测（ReAct + HITL）
用户点“AI建议”或定时任务 -> `AgentController` 检测写意图 -> 若 `confirmCreate!=true` 返回 `needConfirm` 前端二次确认 -> `ChatClient` 携带 `InventoryTools/PurchaseTools/CatalogTools/ContractTools` -> LLM 多轮 `planner->tools->reflector` 自主选择 `listLowStock/getInventory/listSuppliers/createPurchaseOrder` -> `ToolSecurity` 鉴权 + `IdempotencyService(10min)` + 入参校验 + DRAFT 默认需审批 -> 前端展示建议 + 采购单表新增 DRAFT

Python 深度推理：前端 `useDeep=true` 且边车健康时 `PythonSidecarService` 经 `RestClient(超时+指数退避3次+熔断30s)` 调 `POST /api/reason`（同时透传发起用户 JWT，边车回环只读工具以此身份调 Java，审计归属真实用户），LangGraph 在 Python 侧做规划与工具调度，失败降级 Java 直连。**边车刻意只暴露 6 个只读工具**——写操作（`createPurchaseOrder`）收敛在 Java 可信执行层走 HITL/鉴权/幂等，属设计而非遗漏。

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

## 设计决策记录（面试高频"为什么"）

| 决策 | 理由 | 代价与边界 |
| --- | --- | --- |
| Java 编排 + Python LangGraph 边车双语言 | Java 承接企业底座与治理；ReAct/interrupt/checkpointer 生态在 LangGraph 侧成熟；边车不可用自动回退 java-direct，可用性不降档 | 跨语言 trace 与身份透传成本（TraceContext + JWT 透传）；运维两个服务 |
| HITL 双层：图内 interrupt + java-direct 关键词闸门兜底 | interrupt 基于模型真实工具调用决策，语义强、不可被措辞绕过；java-direct 路径没有图，关键词闸门（LLM 分类器兜底、强动作词 fail-closed）防绕过 | 两处闸门需保持语义一致 |
| 写操作收敛在 Java 可信执行层 | ADMIN 鉴权/幂等/DRAFT 状态机等企业约束不随模型能力漂移；Python 侧只做取证 | 多一跳工具回环调用 |
| MCP 写工具默认不暴露 | MCP 传输通道没有人工批准环节，暴露即绕过 HITL；`MCP_ENABLE_WRITE=1` 显式开启后执行层约束仍生效（纵深防御不减层） | 外部 MCP 客户端默认只能读 |
| checkpointer 缺省 MemorySaver，`CHECKPOINT_URI` 切 Postgres | 演示/测试零依赖开箱即用；生产可恢复（跨重启/多实例），Postgres 不可达诚实降级 + 大声告警 | 单机 MemorySaver 重启丢挂起状态 |
| 失败不伪装（ok=false 信封 / degraded 显式化） | agent 的信任边界：取证失败必须如实拒答（degrade_reason），禁止编造；降级路径与成功路径在台账/前端可区分 | 调用方需处理 degraded 形态 |
| 评测双维：答案 LLM-as-judge + 工具轨迹 | 只评答案会漏"答对了但过程乱"（误调度/漏调度）；轨迹 precision/recall/f1 卡过程质量 | 轨迹集需维护 golden 期望序列 |
| Token 双口径（actual 优先 / estimated 打标） | 网关不回传 usage 时观测不中断，但估算永不伪装成真实用量（token_source 随 trace 落库）；Java 台账独立走 jtokkit | 两套口径并存需在报表中标注来源 |

## 扩展与治理

- 治理后台：`/admin/agent/runs`（筛选+回放）、`/admin/agent/costs`（ECharts）、`/admin/agent/prompts`（版本+回滚）、`/admin/agent/eval`（趋势+失败样本），前端路由 ADMIN 守卫
- 评估：`golden_rag.jsonl` 60 条 + `test_agent_eval` + `llm_judge`（faithfulness/relevance 0-2），产出 `docs/eval-report-*.md`，CI mock 门禁
- MCP：`@Tool` 可暴露为 MCP Tool
