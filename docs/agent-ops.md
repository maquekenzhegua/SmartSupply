# Agent 观测 / 评测 / 治理

## 观测
- 异步落库：ObservationService 计数器 + 线程池(2,4,60s,1000) 写入 agent_run/step/tool_call，失败仅告警
- 跨语言 trace：TraceIdFilter 生成 X-Trace-Id，经 TraceContext ThreadLocal + MDC 透传至 PythonSidecar，回传 trace/tool_results/iters 落 agent_step
- 边车工具回环鉴权：Java /chat 将发起用户的 JWT 经 Authorization 头透传给边车 /api/reason，边车调用 Java 只读工具时带上同一 Bearer → CurrentUser/审计归属真实用户（此前依赖未文档化的 JAVA_JWT_TOKEN 服务账号，缺失时回环 403 被静默吞成空结果）；无用户上下文直调边车时回退 JAVA_JWT_TOKEN/自动登录 SIDECAR 账号，回环失败以 auth_failed 显式带出不伪装成空数据
- Token 真实化：MuseSparkChatModel 用 ThreadLocal TokenContext 避免并发串号；TokenEstimator 接 jtokkit cl100k_base，成本按可配置单价
- 指标：agent.chat.latency/tokens/cost、ttft、tool.count/latency、rag.rerank.count，可经 /actuator/prometheus 抓取

## 评测
- 数据集：golden_rag.jsonl 60 条（RAG 20 + 工具 10 + HITL 10 + 注入 10 + 扩展 10），1024 维一致
- 离线门禁：test_golden_eval + test_agent_eval；test_offline_recall_and_answer_keyword_coverage 实测 avg_keyword_hit≈0.73（断言阈值 ≥0.65）；llm_judge mock+bigram≈0.89。旧文档"0.90"为 20 条历史口径，扩到 60 条后已下修
- LLM-as-judge：scripts/llm_judge.py，faithfulness/relevance 0-2，产出 docs/eval-report-*.md，CI mock 门禁阻断
- 轨迹评测（2026-09-06）：scripts/eval_trajectory.py + tests/golden_trajectory.jsonl 14 条——评"工具调用序列是否合理"而非只评答案。指标：precision=|actual∩acceptable|/|actual|（卡误调度）、recall=|actual∩acceptable|/|expected|（卡漏调度，等价替代路径不扣分）、f1、order_match（严格序列一致，报告不门禁）。写工具不进评测集（interrupt 需人工交互）。CI mock 门禁 --min-tool-f1 0.9（当前 f1=1.0），check_gate.py 支持 --min-tool-f1

## 治理
- Runs：按用户/会话/mode/时间筛选，详情含步骤时间线与工具输入输出
- Costs：按天/类型/用户聚合 ECharts
- Prompts：prompt_version 表，激活版 + 回滚
- Eval：反馈统计 + 报告索引
- 权限：/api/admin/** 需 ADMIN，@EnableMethodSecurity

## 成熟度契约（2026-09-05）
- 失败不伪装：Python 工具层任何失败（4xx/5xx/鉴权/连接）返回 ok=False 信封，demo 假数据降级已全部移除；reasoner 对"全部取证失败"直接如实拒答（degrade_reason=all_tool_calls_failed），部分失败时在提示中显式列出不可用的数据源，禁止编造
- LLM 失败显式化：llm.chat 真实模式失败抛 LLMUnavailable（不再降级 Mock 文本冒充模型输出）；/api/reason 响应带 degraded+degrade_reason；Java 端 degraded 的 run 记为 DEGRADED（mode=python-deep-degraded），不混入成功台账；degraded 回复在 API 响应与前端均带 ⚠ 标注
- 规划真实性：planner/reflector 走 function-calling + 参数 schema 校验（缺必填/坏类型即丢弃并记录，不再伪造默认值如 contract_id→1）；关键词规则只存在于 Mock provider（trace 标 provider=mock，评测可甄别），图内不再有伪装规划的 if/else
- HITL 全路径：/chat 与 /chat/stream 均有写意图闸门（关键词快路径 + LLM 分类器兜底，分类器故障按强动作词 fail-closed）；confirm 事件经 SSE 下发，前端二次确认后带 confirmCreate 重发（此前流式路径可整体绕过确认 UX）。2026-09-06 起深度模式升级为图内 interrupt/resume（见下），关键词闸门仅保留为 java-direct 兜底
- 深度模式链路：stream 端点支持 useDeep 并透传 Authorization（此前流式忽略深度开关）；边车回环身份=被委托用户 JWT，服务账号仅作兜底
- 深度事件流（2026-09-05）：新增边车 POST /api/reason/stream（SSE），graph 每节点完成即产出 plan/tool/reflect/reply/done 事件；Java streamReason 用 JDK HttpClient（显式 HTTP/1.1，uvicorn/h11 拒绝 h2c Upgrade）逐事件消费并转发为前端 event:trace，回答切块下发；done 带 runId/degraded/tools，落库 steps 含真实工具入参；流式不重试（避免事件重复），连接失败回落 java-direct
- HITL 图内化（2026-09-06）：写工具 create_purchase_order 进 LangGraph 图，独立 write_tools 节点执行前 langgraph interrupt() 挂起 → confirm_required 事件（thread_id/tool/args/question）→ 前端批准/拒绝 → 携 Command(resume={"approved":bool}) + 同 thread_id 恢复。checkpointer 经 checkpointing.py 工厂选择：缺省进程内 MemorySaver（演示/单机/测试），配置 CHECKPOINT_URI 时 AsyncPostgresSaver 落库（重启/多实例可恢复；Postgres 不可达诚实降级 MemorySaver + 大声告警），thread 生命周期=单次运行，每次运行独立 thread_id。恢复不存在的中断（边车重启）显式 degraded no_pending_interrupt，不伪造成功。深度模式下 Java isWriteIntent 关键词闸门跳过（interrupt 是更强闸门），java-direct 保留关键词闸门兜底。run 台账：挂起记 WAITING_CONFIRM，用户消息即时入记忆，恢复完成补 assistant 回复
- 写工具执行层（2026-09-06）：新端点 POST /api/agent/purchase-orders 复用 PurchaseTools.createPurchaseOrder 全套硬约束（ADMIN 鉴权随透传 JWT 裁决/存在性校验/幂等/独立事务/DRAFT），@RateLimit 20/min；边车侧 tool_create_purchase_order 回环调用，Result.fail 转 4xx HTTP 状态码使错误信封携带真实原因
- 可观测重轨（2026-09-06）：Langfuse 可选接入（app/observability.py）。未配置 LANGFUSE_PUBLIC_KEY/SECRET_KEY 整体 no-op 零开销；配置后一次 agent 运行=一条 trace（thread_id 进 metadata，session_id 聚合会话），planner/reflector-llm 与 reasoner 为 generation（含 token usage），每次只读/写工具取证为 span。埋点全 try/except：观测故障绝不拖垮推理。自部署：docker compose --profile obs up -d langfuse（langfuse:2 + 独立 langfuse 库），compose 内置 headless 初始化（LANGFUSE_INIT_* 自动建 org/project/user/固定演示 key，幂等），一键生成 trace：python scripts/gen_langfuse_traces.py。版本矩阵：SDK 2.x ↔ Server v2（单容器轻量）；Server v3（OTel 原生）对应 SDK 3.x，升级只需改 observability.py 封装层
- generation 用量补丁（2026-09-06 晚）：真实 trace 曾全部 usage 为空——双因：①planner/reflector 调用点漏传 usage；②provider 网关不回传 usage 时无兜底。修复：llm.usage_or_estimate（真实回传 source=actual 优先，缺失时本地粗估并显式 source=estimated，估算只进观测层、Java 台账仍走 jtokkit 估算）；observability 双格式落库（usage(ModelUsage) 供 v2 服务端解析 + usageDetails 供 v3 前向兼容），来源随 metadata.token_source 落 trace，绝不把估算伪装成真实用量。实测：reasoner usage=12/36、planner-llm usage=210/1（均 token_source=estimated）
- 超时预算：边车 read timeout 90s（多步 ReAct 现实需要），connect 5s；边车整体不可用时熔断降级 java-direct，台账 mode 保持真实
- 超时预算（真模型校准 2026-09-05）：非流式 /api/reason 单次真实推理模型可到 1~4 分钟（curl 实测 46s，多步 141~150s），90s 读超时会以 "extracting response" 截断形态失败且绕过 isRetryable → 独立配置 blocking-timeout-ms=300s；流式 request timeout 600s；SSE emitter 深度模式 600s/普通 120s；openai SDK 显式 timeout 240s。教训：mock 秒回定出的超时预算对真模型全部失效，预算必须按真实时延校准
- 用户级轨迹：新增 /api/agent/runs/{runId}/trace（本人或 ADMIN），非流式对话前端带"查看推理轨迹"折叠面板，agent 的 planner/tool/reflector 步骤与真实工具入参对普通用户可见
- 召回真实性：Python /api/rag/recall 从固定文案占位改为转发 Java 真实召回，Java 不可达时 502+degraded，不返回编造 context

## 业务闭环（2026-09-06 补强）
- 审批 RBAC：PUT /api/purchase-orders-extra/{id}/status 强制 ADMIN（此前仅 authenticated，任何登录用户可把 DRAFT 直接改 APPROVED，"人审"话术在 API 层不成立）；前端藏按钮不构成防线
- 审批留痕：V4 迁移新增 purchase_order.approver/approved_at，APPROVED 时落当前用户与时间；两份 H2 schema 已同步
- 状态机：DRAFT→APPROVED→RECEIVED（非终态可 CANCELLED），CAS 流转（WHERE status=当前值）防并发/重复放行，终态不可变；已生效（APPROVED/RECEIVED）采购单禁止物理删除
- 采购→库存闭环：RECEIVED 自动按采购明细入库（入该 SKU 主仓，无库存行则建 1 号仓）并写 inventory_flow 流水，与手动调整同一套账，同一事务原子完成（BusinessFlowTest 验证差值+流水条数）
- 重排诚实化→真 BM25：Reranker 的 bm25Like（无 IDF 的词频启发式，面试追问 IDF 即穿帮）重写为真 BM25（k1=1.2/b=0.75 词频饱和+长度归一），IDF 在 top-8 候选集局部统计（类注释与 interview-script 均如实标注口径，可经 CrossEncoderReranker 同接口升级）；中文分词查询/文档统一 CJK 双字对齐，删除 contains 模糊匹配
- 限流拓扑修复：frontend nginx 由 append（$proxy_add_x_forwarded_for，首段客户端可伪造→trust-proxy 时限流可刷穿）改覆写 X-Forwarded-For=$remote_addr（单一可信代理语义）；prod compose 显式 SMARTSUPPLY_RATELIMIT_TRUST_PROXY=true（不开则全体用户共享 nginx IP 一个限流桶互相锁死）

## 实跑验证（2026-09-06 晚）：两个"生产就绪"收尾 + 暴露并修复的 4 个真实问题

收尾目标：① checkpointer 持久化从"应换"变"已支持"；② Langfuse 从"代码集成"变"实跑出真实 trace"。实跑暴露的问题（此前仅语法级校验/单测覆盖不到的集成缝隙）：

1. compose initdb 嵌套挂载崩溃：postgres 把单文件 999-create-langfuse-db.sh 挂进整目录只读挂载的 /docker-entrypoint-initdb.d，Docker 需在只读挂载内创建挂载点 → read-only file system 启动失败。修复：整目录挂 deploy/postgres-init（schema 单一事实源仍为应用内 Flyway，entrypoint 只管辅助库）
2. Langfuse SDK/Server 版本错配：SDK 3.15.0 只兼容 Server v3（OTLP 端点 + v3 projects 响应结构），对 v2 服务端 auth_check 直接 ValidationError。决策：保留单容器 v2 服务端，SDK 降 2.60.10 经典 API（observability.py 封装层签名不变，调用方零改动）；升级 v3 需额外 ClickHouse/MinIO/Redis，演进路径写进版本矩阵注释
3. PurchaseOrderWriter created_by 类型：BIGINT 列被塞用户名字符串，PG 必报错——该路径此前只有 Python 侧打桩测试，Java 侧从未真实落库，68 用例全绿也没暴露。修复：事务内解析 sys_user.id（缺失不阻断，用户名留 remark 审计）；新增 BusinessFlowTest.agentWriteCreatesRealPurchaseOrderWithCreatorId 真插库回归
4. 测试污染共享 Redis：上述测试的幂等 key（成功路径按设计不释放，TTL 10min）写进真实 localhost:6379，把 HITL 恢复演练的执行拦成"重复提交"。排查路径（面试可用）：Langfuse trace 确认恢复链路正常 → Redis TTL 反推 key 设置时刻 → 对齐测试运行时间线 → 定位测试隔离缺失。修复：测试改独立参数（quantity=42）+ 前置/收尾清理

checkpointer 持久化验收（scripts/hitl_restart_drill.py，跨两个独立进程）：suspend（interrupt 挂起 → 进程退出）→ resume --approve（新进程凭 CHECKPOINT_URI 从 Postgres 恢复 → 真实创建 PO-E0379E4F，created_by 正确关联 admin，明细同事务落库）。Windows 本地注意：Python 默认 ProactorEventLoop 与 psycopg 异步模式不兼容，脚本已内置 WindowsSelectorEventLoopPolicy（uvicorn 同此处理）；降级口径实测生效（连不上 Postgres 时 MemorySaver 兜底 + 告警，主流程不受阻）。Langfuse 实跑：9+ 条真实 trace（6 条 smoke 全走 Java 工具回环 + 演练挂起/恢复），观测点含 plan/tool span、reflector 决策、reasoner generation（model=mimo-v2.5）

## 可证明成熟（2026-09-07）：Agent 前沿生态 + 全栈四件套

**Agent 前沿补齐：**
- **MCP 服务器实做**（app/mcp_server.py，官方 SDK mcp==1.27.2，stdio 传输）：8 工具按开放协议暴露，任意 MCP 客户端可发现可调用。关键设计判断：**写工具默认不暴露**——MCP 传输通道没有图内 interrupt 的人工批准环节，暴露即绕过 HITL；MCP_ENABLE_WRITE=1 显式开启，且 Java 执行层 ADMIN/幂等/DRAFT 仍生效（纵深防御不减层）。协议级测试 test_mcp_server.py 4 用例（真实内存会话：工具发现/调用/写默认关/未知工具如实报错）。
- 多智能体选型叙事、agentic RAG 点破（reflector 补充取证=最小形态）、记忆演进边界（mem0/Zep 类为 awareness）：见 interview-script.md "前沿对标"节。

**全栈可证明成熟（"看起来成熟"→"数字证明"）：**
- **Testcontainers 真 PG 回归**（PostgresRegressionTest，pgvector/pgvector:pg16）：H2 双源漂移曾让 created_by 类型 bug 全绿通过——现在真 PG + 真实 Flyway 迁移（V1→V4）进回归范围，3 用例（迁移列存在/BCrypt 账号、agent 写 created_by 关联、采购生命周期 CAS+自动入库）；disabledWithoutDocker 无 Docker 自动跳过，CI ubuntu runner 真实执行。
- **Playwright E2E 主链路**（frontend/e2e，`npm run e2e`）：登录 → 库存页种子数据 → Agent 写意图 → 写闸门二次确认 UX 闭环。E2E 内关闭"流式"开关走非流式：vite dev 代理对"毫秒级完成"的 SSE 闸门快路径有缓冲怪癖（吞首事件到超时，dev-only），非流式路径完全确定且写闸门逻辑两路共用；落库断言归 API 层/演练。CI 独立 e2e job（起栈→打 jar→playwright）。
- **SSE 反代缓冲修复（E2E 排查的副产品，prod 相关）**：java-direct 写闸门的 confirm 事件是"毫秒级发送+完成"的 SSE 快路径，经 vite dev 代理实测首个事件被吞到超时；排查后给 `/api/agent/chat/stream` 响应补 `X-Accel-Buffering: no`（ResponseEntity 头）——nginx 生产环境同样会缓冲小体积 SSE 事件，此头显式关闭。同时闸门事件改与主链路一致的异步线程发送。
- **k6 读链路压测**（deploy/loadtest/k6-smoke.js，50 VU 稳态 60s）：**94,929 请求 0 失败，P95=77ms、中位 15ms**（含库存分页/低库存/供应商/Agent 对话 mock 编排链路）。口径：SMARTSUPPLY_RATELIMIT_ENABLED=false——压数据库而非限流器，限流开启时流量会被 30/min 桶主动塑形为 429。
- 缓存/MQ/k8s 维持"何时需要"演进叙事（interview-script），不过度工程。

## 数据模型
agent_run(trace_id, user, session, agent_type, mode, latency, tokens, cost, token_source) -> agent_step(seq, node, digest) -> agent_tool_call(tool, args, result_digest)
user_feedback(rating, comment) 驱动评测候选
