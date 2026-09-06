# SmartSupply 测试与量化报告（面试/简历 STAR 可直接引用，离线可复现）

> **2026-09-07 更新（可证明成熟：MCP 实做 + Testcontainers 真 PG 回归 + Playwright E2E + k6 压测数字）**：
> - **MCP 协议实做**：`app/mcp_server.py`（官方 SDK mcp==1.27.2，stdio）把 8 工具按开放协议暴露；写工具默认不暴露（MCP 通道无 interrupt 人工批准环节，暴露即绕过 HITL），MCP_ENABLE_WRITE=1 显式开启且 Java 执行层 ADMIN/幂等/DRAFT 仍生效。`test_mcp_server.py` 4 用例走真实内存会话（工具发现/调用/写默认关/未知工具报错）。agent-python 回归 **78 passed + 3 gated skipped**。
> - **Testcontainers 真 PG 回归**：`PostgresRegressionTest` 起 pgvector/pgvector:pg16，走真实 Flyway V1→V4 迁移 + 真 PG SQL，3 用例（V4 列存在 + BCrypt 账号、agent 写 created_by 关联、采购生命周期 CAS + 自动入库差值）；`disabledWithoutDocker` 无 Docker 自动跳过，CI ubuntu runner 真实执行。修复"H2 双源漂移让 created_by bug 全绿通过"这一盲区。
> - **Playwright E2E 主链路**：`frontend/e2e/main-flow.spec.ts` 2 用例 **passed（3.1s）**——登录 → 库存种子数据 → Agent 写意图 → 写闸门二次确认 UX 闭环（java-direct + AI_MOCK，无外部依赖；流式开关关闭走非流式，规避 vite dev 代理对快路径 SSE 的缓冲怪癖）；CI 独立 e2e job（compose 起栈 → 打 jar → playwright）。
> - **k6 读链路压测**（deploy/loadtest/k6-smoke.js，50 VU 稳态 60s）：**94,929 请求 / 0 失败，P95=77ms、中位 15ms、avg 26ms**（库存分页/低库存/供应商/Agent 对话 mock 编排链路）。口径注明：SMARTSUPPLY_RATELIMIT_ENABLED=false（压数据库而非限流器）。
> - backend 回归（H2 全量 + PG IT）：**69 + 3 = 72 passed**。
>
> **2026-09-06 晚间更新（实跑收尾：checkpointer 持久化 + Langfuse 实跑，"生产应换"全部变"已支持"，最新权威口径）**：
> - **checkpointer 可插拔持久化**：新增 `app/checkpointing.py` 工厂——缺省进程内 MemorySaver（演示/单机/测试零依赖），配置 `CHECKPOINT_URI` 后 AsyncPostgresSaver 落库（连接参数与官方 from_conn_string 对齐：autocommit+prepare_threshold=0+dict_row——`CREATE INDEX CONCURRENTLY` 迁移不能在事务块内跑是实跑踩出来的）；Postgres 不可达诚实降级 MemorySaver + 大声告警（与幂等降级同口径）。graph 单例改惰性构建（AsyncPostgresSaver 构造绑定 event loop，须在首个请求协程内）。新增 `test_checkpointer.py` 3 用例。dev/prod compose 均默认注入 CHECKPOINT_URI。
> - **HITL 跨进程恢复演练（真验收）**：`scripts/hitl_restart_drill.py` 两个独立进程——suspend（interrupt 挂起后进程完全退出）→ resume --approve（新进程凭 Postgres checkpoint 恢复）→ **真实创建 PO-E0379E4F（总额 990，created_by 正确关联 admin，明细同事务落库）**。同一演练在 MemorySaver 下必然 no_pending_interrupt 失败，对比即验收标准。Windows 注意：默认 ProactorEventLoop 与 psycopg 异步不兼容，脚本内置 WindowsSelectorEventLoopPolicy（uvicorn 同此处理）。
> - **Langfuse 实跑**：compose obs profile 无头初始化（LANGFUSE_INIT_* 幂等建 org/project/user/演示 key，免手工注册）→ `gen_langfuse_traces.py` 一键 6 条 smoke trace（真实 LLM 规划 + Java 工具回环全通）→ API 验证 9+ 条 trace，观测点含 tool/plan span、reflector 决策、reasoner generation（model=mimo-v2.5）。**版本矩阵实跑修正**：SDK 3.x 仅兼容 Server v3，v2 服务端配 SDK 2.60.10（requirements 注明升级路径，observability.py 封装层签名不变）。
> - **实跑暴露并修复 4 个真实问题**（详见 docs/agent-ops.md "实跑验证"节）：① compose initdb 单文件嵌套只读目录挂载 → 容器无法启动（改整目录挂载，schema 单一事实源仍为应用内 Flyway）；② Langfuse SDK/Server 版本错配（从未实跑所以没暴露）；③ PurchaseOrderWriter 把用户名塞 created_by BIGINT 列——该路径 Java 侧从未真实落库、68 用例全绿也没暴露（修复：事务内解析 sys_user.id，新增 `agentWriteCreatesRealPurchaseOrderWithCreatorId` 真插库回归）；④ 测试幂等 key 污染共享 Redis 拦下演练恢复执行（测试改独立参数 + 前后清理）。
> - **generation 用量补丁（诚实性收尾）**：真实 trace 曾 generation usage 全空（planner/reflector 调用点漏传 + 网关不回传时无兜底）。修复：llm.usage_or_estimate 真实优先/缺失本地粗估并显式 source=estimated（估算只进观测层，Java 台账仍走 jtokkit）；observability 双格式落库（usage 供 v2 服务端 + usageDetails 供 v3），来源落 metadata.token_source。实测 reasoner usage=12/36（estimated）。新增 `test_llm_usage.py` 4 用例。
> - 回归状态：agent-python **74 passed + 3 gated skipped**；backend **69 passed 0 failed（5 gated skipped）**；演练与 trace 证据见 agent-ops.md。
>

> **2026-09-06 更新（HITL 图内化 + Langfuse + 轨迹评测，Agent 层四项能力补齐，最新权威口径）**：
> - **HITL 升级为 LangGraph 原生 interrupt/resume**：写工具 `create_purchase_order` 进图内独立 `write_tools` 节点，执行前 `interrupt()` 挂起 → 前端批准/拒绝 → `Command(resume)` + 同 thread_id 恢复；图编译挂 MemorySaver checkpointer（生产应换 AsyncPostgresSaver）。未批准永不执行；恢复不存在的中断显式 degraded。新增 `test_hitl_resume.py` 4 用例；Java 新增 `/api/agent/purchase-orders` 执行端点（复用 ADMIN 鉴权/幂等/事务），挂起 run 记 WAITING_CONFIRM。深度模式下 Java 关键词闸门跳过（interrupt 是更强闸门），java-direct 保留兜底。
> - **Langfuse 可选接入**：`app/observability.py`，未配置 key 整体 no-op 零开销；配置后一次运行一条 trace（thread_id 关联键），planner/reflector/reasoner 为 generation（带 provider 真实 token usage），每次工具取证为 span；埋点全 try/except，观测故障绝不拖垮推理。compose `obs` profile 自部署（langfuse:2 + 独立库）。`test_observability.py` 3 用例。
> - **轨迹评测上线**：`eval_trajectory.py` + `golden_trajectory.jsonl` 14 条——评"工具调用序列是否合理"：precision=|actual∩acceptable|/|actual| 卡误调度、recall 分母为 expected 卡漏调度、**等价替代路径不扣分**（acceptable_tools 机制）、order_match 严格一致仅报告。mock 口径 **avg_tool_f1=1.000（P=1.0 R=1.0）** 进 CI 门禁 `--min-tool-f1 0.9`（check_gate.py 已扩展）；写工具不进评测集（HITL 需人工交互）。`test_trajectory_eval.py` 4 用例。
> - **沙箱口径成文**（`docs/interview-script.md` "能力边界"节）：本 Agent 不涉及任意代码执行，能力边界收敛在 8 工具白名单 + SQL 只读网关（只读连接/5s 超时/1000 行/校验器）+ 写操作双闸门（图内 interrupt + Java ADMIN/幂等/DRAFT）+ 回环白名单，升级路线（Docker 容器沙箱 → gVisor/Firecracker）一并写明。
> - **业务与部署补强（同日追问自查后修复）**：① 采购审批 API 强制 ADMIN + 状态机 CAS（DRAFT→APPROVED→RECEIVED，防并发/重复放行）+ 审批人/时间留痕（V4 迁移，H2 双 schema 同步），`purchaseStatusChangeRequiresAdmin` 用 OPS 与匿名双向验证 403；② RECEIVED 自动按明细入库并写 `inventory_flow`（采购→库存同一套账，事务原子，BusinessFlowTest 差值断言）；③ Reranker 从"无 IDF 的词频启发式（曾自称 BM25）"重写为真 BM25（k1=1.2/b=0.75 + 候选集局部 IDF，口径在代码与剧本中如实标注），`RerankerTest` 4 用例验证稀有词判别与 tf 饱和次线性；④ 限流反代拓扑修复：nginx 覆写 X-Forwarded-For=$remote_addr + prod compose 显式 trust-proxy=true（原配置两个方向都不成立：不开则共享桶互锁，开了则首段可伪造刷配额）。
> - 回归状态：agent-python **67 passed + 3 gated skipped**；backend **68 passed 0 failed（5 gated skipped）**；frontend vitest **7/7**、ESLint 0 error、build ✓；compose 两套 config 校验通过（dev 全 profile、prod 含 obs）。
>
> **2026-09-05 更新（加固后代码复跑，评测权威口径）**：在完成真鉴权/SQL 只读网关/真流式/并行取证等加固后，同口径（MiMo-V2.5 + Ollama qwen3-embedding 向量召回）重跑 60 样本真实评测：
> - **avg_keyword_hit=0.842、avg_evidence_recall=0.917、faithfulness=1.55/2、relevance=1.83/2**，与 09-04（0.858/0.917/1.55/1.82）一致性误差 ≤0.016 → **结果可复现，非单次运气**。
> - 数值门禁 `scripts/check_gate.py --min-hit 0.6` GATE PASS（CI 门禁已从 grep 字符串升级为数值阈值判定）。
> - **评测证据已可入库展示**：新增脱敏汇总版 `docs/eval-report-real-summary.md`（仅聚合指标+逐题数值，剥离模型原始输出）；完整报告按 `.gitignore` 约定留本地，`scripts/strip_eval_report.py` 负责从完整报告生成汇总。
> - 失败归因：15/60 部分失分，主力是 fh=1（LLM 裁判对多点答案只给部分忠实分）与 2 条 evid=0（HITL 行为类问题的证据未进召回 top-k，属检索覆盖问题而非模型幻觉）。
>
> **2026-09-04 更新（真实评测重跑 + 归因修正）**：模型 = MiMo-V2.5（opencode zen/go，走标准 `/chat/completions`），召回 = 真实 Ollama `qwen3-embedding:0.6b` 向量 top-4（不再用 bigram 双字代理）。
> - 60 样本：**avg_keyword_hit=0.858、avg_evidence_recall=0.917、faithfulness=1.55/2、relevance=1.82/2**。
> - 关键结论：旧的 0.650 低分**主因是评测脚本用 bigram 双字重合代理召回**（把证据漏掉了），而非模型能力；换真实语义召回后 hit 从 0.650→0.858、evidence 从 ~0.68→0.917。
> - RAG 冷启动已补：`knowledge_doc` 原只有 2 篇随迁移种入，工具/系统参数类知识此前从未入库；现新增 6 篇种子 + `KnowledgeSeeder` 走真实 ingestion，线上开箱即可召回。
> - CI 后端门禁已从"4 个纯单元测试类"扩为**全量 `mvn -B test`**：56 用例（51 通过 + 5 个真实模型门控跳过）0 失败；并修复 `initializeSchema` H2 启动回归与限流的"本机有无 Redis"环境依赖（详见 diagnosis 第 3 节补充）。
> - Agent 层 2026-09-04 夜间加固：sidecar 工具回环改透传真实用户 JWT（身份/审计归属正确，无 token 时回环失败显式带出而非吞成空），幂等/限流 Redis 故障本地兜底，工具调用 JSON 持久化 SQL 改三库兼容，HITL 写意图关键词扩面。
> - 详见 `docs/rag-eval-diagnosis-2026-09-04.md` 与 `docs/eval-report-real-summary.md`（真实评测的**脱敏汇总版**，已入库可直接查阅；完整报告含模型原始输出，按 `.gitignore` 约定仅在本地留存，可用 `scripts/llm_judge.py --mode real` 复现）。以下 08-29/09-02 段落为历史口径（muse + bigram 代理），保留供对照。
>
> 生成时间：2026-08-27 23:00（本地离线，H2 + Mock，无真实 LLM/PG/Redis）
> **2026-08-29 更新（真实模型评测已跑通）**：网关 = opencode zen/go（`/responses` 协议），模型 = muse-spark-1.2-contributor。
> - Java 侧：`MuseSparkChatModelRealTest`（EVAL_REAL_LLM 门控）2/2 通过——同步 call 返回 313 字合规风控分析；**真流式 stream 从真实网关收到 71 个 delta chunk**，端到端 SSE 链路验证完毕。
> - Python 侧：`test_real_llm_golden_eval`（EVAL_REAL_LLM 门控）通过。首轮（muse + 无排序召回）：hit 0.700；**修复召回排序（bigram 重合度 top-2，对应真实链路的 rerank）并切 mimo-v2.5 后：hit 0.800 / recall 0.800 / faithfulness 0.652，16/20 满分**（离线 mock 自证口径 0.875/0.800/0.865）。
> - 首轮失分归因：答案素材（工具名、系统参数、金额）都在 golden contexts 中，但无排序的召回代理没把最相关上下文排进前 2——按重合度排序后绝大多数恢复命中，说明失分在召回质量而非模型幻觉；模型在上下文外问题上一贯拒绝编造，与“依据不足直说”的反幻觉设计一致。
> - opencode 网关无 `/embeddings` 端点。**Embedding 已切本地 Ollama**：`qwen3-embedding:0.6b`（1024 维，免费离线），生产同路径 `OllamaEmbeddingRealTest` 验证——维度与 VECTOR_DIMENSIONS 一致，中文语义区分度 gap≈0.47（相关对 0.672 vs 无关对 0.203；此前实测 snowflake-arctic-embed 中文 gap 仅 0.01，不合格弃用）。全项目维度统一 1024（Mock 向量跟随配置、H2/PG 建表、VectorStoreConfig）。**存量库迁移**：`DROP TABLE IF EXISTS vector_store;`（旧 1536 维表）后重启自动按 1024 重建，再重新上传知识文档入库。
> 复现（Key 从环境变量注入，勿写死在仓库）：
> - `EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=https://opencode.ai/zen/go/v1 AI_MODEL=muse-spark-1.2-contributor mvn -B test -Dtest=MuseSparkChatModelRealTest`（backend/）
> - `EVAL_REAL_LLM=1 OPENAI_API_KEY=... OPENAI_BASE_URL=... AI_MODEL=... python -m pytest tests/test_golden_eval.py -m real_llm -s -v`（agent-python/）

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
mvn -o test -f backend/pom.xml
cd agent-python && python -m pytest tests/ -v
cd agent-python && python -m pytest tests/test_golden_eval.py tests/test_tool_accuracy.py -s -v
```
