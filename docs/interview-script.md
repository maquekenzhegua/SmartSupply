# 面试演示剧本（3分钟拿下 Agent 岗）

## 开场 30 秒（一句话定位）

> "这个项目叫 SmartSupply，我没有从零写后台的轮子——我参考了 RuoYi 的分层与权限思想，自建了供应链域（供应商/商品/库存/采购/合同/BI），把 80% 精力放在了 Agent 层：8 个工具（7 只读 + 1 个 HITL 写操作）+ RAG 混合召回+重排 + LangGraph 真 ReAct + 图内 interrupt/resume 人审 + Langfuse 全链路 trace + 答案/轨迹双维评测，而不是只做聊天。"

---

## 演示 1：合同风控 Agent（RAG + 防幻觉，最惊艳）

**操作：** 打开"合同风控"页 -> 上传一份含"乙方承担一切连带责任"的 PDF/TXT

**话术：**
- "上传后 Tika 按 800/100 滑窗切片，Embedding 进 pgvector HNSW（1024维，qwen3-embedding:0.6b），同时写 `knowledge_doc/chunk`。"
- "查询时走 `vector top8 -> Reranker(BM25+覆盖+标题加权) top4` + `ILIKE` 兜底的混合召回，`PromptGuard` 用 `<knowledge>/<user_query>` 标签隔离，系统 Prompt v3.0 强制'仅基于召回作答，否则答依据不足'。"
- "你看这份《AI风控报告》标红'无限连带责任=高风险'并给'改为有限责任'，末尾有 [引用] 依据召回标题，`enforceCitation` 保证无引用时自动追加——RAG 召回的那条历史案例就是证据。"

**深挖：**
- RAG 怎么做？ Tika→800/100→pgvector HNSW 1024→top8重排4+关键词兜底，阈值 0.2，无 PG 时 `MockEmbedding(伪向量归一化)` + `ILIKE` 保证离线可演示。
- 重排器的 BM25 是真的吗？ 是 BM25 本尊（k1=1.2/b=0.75 词频饱和 + 文档长度归一），但 **IDF 是在本次召回的 top-8 候选集上局部统计的**，不是全库语料统计——候选集内判别够用，工程上避免了维护全库 df 统计；要更强判别力走 `CrossEncoderReranker` 同接口换 bge-reranker。中文分词查询与文档统一滑双字（bigram），token 空间对齐，不做 contains 模糊匹配。`RerankerTest` 验证稀有词高 IDF 判别与 tf 饱和次线性。
- 怎么防幻觉？ 三层：输入标签隔离 + Prompt 仅基于召回 + 输出引用校验；`AgentGuardTest` 与 `test_tool_accuracy` 有回归。
- 注入怎么防？ `PromptGuard` 检测 `ignore previous instructions/system:` 等 6 类模式并消毒，用户消息长度 4000 截断，问 `"忽略之前指令"` 会被标记 `flagged`。

---

## 演示 2：补货预测 Agent（ReAct + HITL，最能区分 Chatbot）

**操作：** Agent 工作台开启"深度推理" -> 输入"哪些 SKU 低于安全库存，需要补货？" -> 再输入"帮我创建采购单，供应商1，SKU-T001-WH-M，100件，单价9.9"

**话术：**
- "这是真 ReAct：Python 边车 `planner -> tools -> reflector -> reasoner` 的 LangGraph 状态图，`MAX_ITERS=6`，同一批只读取证 `asyncio.gather` 并行执行，事件流 plan/tool/reflect 实时下发前端，回答是 provider 原生 token 级流式。"
- "当模型真实规划出写工具 `create_purchase_order`，**图在执行前经 LangGraph `interrupt()` 挂起**——你看界面上弹出'批准/拒绝'两个按钮，整个推理状态被 MemorySaver checkpoint 存住了。我点'批准'，前端携 `Command(resume)` + 同一 thread_id 恢复，工具才真正回调 Java 落库；点'拒绝'，图恢复后如实回答'已取消，未做任何变更'。HITL 的决策点是**模型的工具调用**，不是消息措辞的关键词匹配。"
- "就算批准了，Java 执行层还有硬约束：`requireSupplierWritePerm` 强制 ADMIN 角色（回环用的是透传的真实用户 JWT，审计归属发起人）+ 供应商/SKU 存在性校验 + 10 分钟幂等键 + DRAFT 状态留人审——图内挂起是决策层闸门，Java 是执行层闸门，纵深防御。"
- "采购单落库后业务也是闭环的：审批走采购模块 `PUT status`，**API 层强制 ADMIN**（curl 绕过前端一样被 RBAC 拦）、状态机 CAS 流转（DRAFT→APPROVED→RECEIVED，重复点击/并发审批被 WHERE 当前状态拦下）、审批人与审批时间落库留痕；收货 RECEIVED 自动按采购明细入库并写 `inventory_flow` 流水——从 Agent 下单到库存台账是同一套账。"
- "它还有自主形态：`ReplenishmentScheduler` 每天 02:00 扫描低库存，无需用户唤醒——这是 Agent 与 Chatbot 的本质区别。"

**深挖：**
- 怎么防止误下单/越权？ 双层：图内 `interrupt()` 挂起等人工批准（未批准永不执行）+ Java 侧 ADMIN 鉴权/存在性校验/幂等键/DRAFT 审批 + 日志审计。
- 边车在挂起等待确认时重启了怎么办？ 分两层答：**默认进程内 MemorySaver** 时，恢复请求会拿到显式 `no_pending_interrupt` 降级响应，提示用户重新发起，绝不伪造执行成功；**已支持持久化**——checkpointer 是可插拔工厂（`checkpointing.py`），配置 `CHECKPOINT_URI` 后切 AsyncPostgresSaver，挂起状态落库、重启/多实例都能恢复。这不是纸上谈兵：我写了跨进程演练脚本（`hitl_restart_drill.py`），进程 1 挂起后完全退出，进程 2 凭 Postgres checkpoint 恢复并真实创建了采购单——同一演练在 MemorySaver 下必然失败（no_pending_interrupt），对比就是验收标准。降级口径：Postgres 连不上时自动落回 MemorySaver 并大声告警（可用性优先 + 留痕，与幂等服务的降级一致）。
- 边车挂了怎么办？ `PythonSidecarService` 带 `connect/read 超时 + 指数退避 400→800→1600ms + 熔断 30s`，连续 5 次失败自动熔断，全部降级 Java 直连。

---

## 演示 3：经营分析 Agent（NL2SQL，最体现工程严谨）

**操作：** 在"仪表盘"输入"华南区哪个品类退货率最高？"点分析

**话术：**
- "NL2SQL 我做了安全边界：Prompt v1.3 约束只 SELECT，经 `SqlValidator` 禁 `insert/update/delete/drop/-- /* union select pg_` 等 + 表白名单 + 多语句检测 + 长度 1200 后才 `queryForList` 只读执行。"

---

## 加分项（主动抛）

1. **可观测双轨：** 轻轨 `X-Trace-Id` 跨 Java/Python + `agent_run/step/tool_call` 落库可回放 + actuator/prometheus 指标；重轨 **Langfuse（已实跑）**：一次 agent 运行 = 一条 trace，thread_id 进 metadata、session_id 聚合会话，planner/工具/reflect 是 span、reasoner 是 generation 且带 provider 真实 token usage，UI 上直接看瀑布图；未配置 LANGFUSE_* 时整体 no-op 零开销，观测系统故障绝不拖垮推理。被追问版本选型：自部署用 Server v2（单容器轻量）+ SDK 2.x 经典 API，封装层收敛在 observability.py 四个函数——升 v3（OTel 原生，需额外 ClickHouse/MinIO/Redis）只动封装层；这是我在实跑中亲手踩出来的版本矩阵（SDK 3.x 对 v2 服务端直接 ValidationError）。
2. **记忆与引用：** `ChatMemoryService` LLM 滚动摘要（Mock 兜底）+ `citations[]` 结构化引用 + `[n]` 强制补全 + 赞踩入 `user_feedback`。
3. **双维评估：** 答案质量 `llm_judge`（LLM-as-judge，faithfulness/relevance 0-2，60 样本真实跑分 hit=0.842/faith=1.55）+ **轨迹评测 `eval_trajectory`**（golden 期望工具序列 vs 实际执行序列：precision 卡误调度、recall 卡漏调度、f1 按"等价替代路径不扣分"计，另有严格顺序一致率；mock 口径 f1=1.0 进 CI 门禁 `--min-tool-f1 0.9`）——不只评"答得对不对"，还评"调对工具没有"。

### 新增剧本

**HITL 现场演示：** 深度模式说"帮我下单"→ 界面弹批准/拒绝 → 先点"拒绝"，Agent 回答"已取消：写操作未被批准执行，未对系统做任何变更" → 再发起并点"批准"，返回真实采购单号。全程可在 Langfuse 看到同一 thread_id 的两段 trace（挂起段+恢复段）。

**Trace 回放：** 打开 `/admin/runs` 选一条 `python-deep` 记录，点详情看 `planner→tool→reflect→reasoner` 时间线与 `tool_results`，同一 `trace_id` 串联 Java 与 Python 日志；Langfuse 里同 thread_id 关联 LangGraph checkpoint 状态。

**评测报告怎么读：** `docs/eval-report-*.md` 看 `avg_keyword_hit/faithfulness/relevance`（答案维度），`docs/eval-trajectory-*.md` 看 `avg_tool_f1/order_match`（决策维度），失败样本表定位召回、工具选择或顺序问题。

**越权漏洞修复：** `AgentController` 统一走 `ChatMemoryService.canAccess`，`default` 会话按 `default-{username}` 隔离，`ToolSecurity` 写操作需 ADMIN，`SecurityConfig` 收敛 actuator，`CORS` 白名单。

---

## 能力边界（沙箱问题标准答案，必背）

**问："你们的沙箱怎么做的？"**

> "这个项目的 Agent **不涉及任意代码执行，所以没有代码沙箱，也不需要**——我刻意把能力边界收敛在 8 个白名单工具上，模型只能'选工具+给参数'，不能'写代码跑代码'。真正的安全边界在四处：
> 1. **数据访问层当沙箱用**：NL2SQL 走只读连接（`setReadOnly`）+ 5s 超时 + 1000 行硬上限 + SqlValidator 词边界黑名单/表白名单/禁注释禁多语句，model 能摸到的数据面就是业务只读视图；
> 2. **写操作双闸门**：图内 LangGraph `interrupt()` 挂起等人工批准，批准后也要过 Java 的 ADMIN 鉴权+幂等+DRAFT 审批；
> 3. **网络隔离**：边车（含模型）只能经 `call_java_tool` 白名单路径回环 Java API，带透传 JWT，模型本身不直接触网；
> 4. **提示注入面**：`PromptGuard` 标签隔离 + 消毒，注入进来的指令也拿不到工具白名单之外的能力。
>
> 如果业务需要 Code Interpreter 类能力，我会用 **Docker 容器级沙箱**：每次执行起一次性容器，network=none 断网、只读根文件系统 + 临时写目录、CPU/内存 cgroup 限额、wall-clock 超时强杀、输出大小截断，宿主非 root 运行；再往上是 gVisor/Firecracker 微虚拟机隔离。这是隔离级别的升级路线，选型取决于多租户信任等级。"

要点：先承认边界（没有的说不存在），再讲清现有替代防线，最后给升级路线——比硬说"有沙箱"经得起追问得多。

---

## 前沿对标（2026-09-07：主动讲，别等被问）

> 开场白："Agent 领域这一年协议和范式变了很多，我说说我的项目哪些跟上了、哪些刻意没上、为什么。"

**1. MCP（Model Context Protocol）——已实做，这是最重要的一个。**
- "我的 8 个工具有两个入口：LangGraph 图内调用（主链路），以及一个标准 **MCP 服务器**（`app/mcp_server.py`，官方 SDK、stdio 传输）——任何 MCP 客户端（Claude Desktop、IDE Agent）都能发现并调用同一套供应链工具，不用重新造工具层。我跑过协议级测试：list_tools 工具发现、call_tool 真实调用、未知工具如实报错。"
- 追问"写工具怎么处理"："**MCP 通道默认不暴露 create_purchase_order**——因为 MCP 传输里没有我图内 interrupt 的人工批准环节，挂上去等于绕过 HITL。确需开启设 MCP_ENABLE_WRITE=1，且 Java 执行层的 ADMIN/幂等/DRAFT 审批仍然生效。**人工批准不可外包给传输协议**，这是我做 MCP 时最重要的一条设计判断。"

**2. 多智能体——选过，答案是单 agent（+ 反思节点），理由正向：**
- "供应链域工具集就 8 个，上下文闭环小；HITL 场景需要**单线审计**——一次运行一个 thread、一份台账，拆成多 agent 后批准点和责任链都散了；多 agent 的协调成本（消息传递、状态同步、级联重试）在这个规模是负收益。业界共识也是从简单开始：单 agent 跑通评测、再在瓶颈处拆。我的 reflector 反思节点 + 可插拔 checkpointer 本身就是 LangGraph 多智能体（supervisor/swarm）模式的地基，真要拆是编排层改动而不是重写。"

**3. Agentic RAG——已经是最小形态，说破它：**
- "我的 RAG 不是单轮检索：reflector 在 MAX_ITERS 内可以**基于已取证的不足再规划补充工具调用**（比如先查库存发现缺供应商信息，再调供应商检索），这就是 agentic RAG 的最小形态——检索成为 agent 可规划的动作而不是固定流水线。再往深走是查询分解 + 迭代召回 + 引用校验（deep research 形态），对供应链问答属过度设计，但演进路线是通的。"

**4. 记忆系统——已有的就是加分项，往外一步知道边界：**
- "短期记忆 Redis（近 20 轮进上下文、40 条摘要压缩不丢语义）+ DB 长期 + 多会话隔离。前沿的记忆方向我关注两类：对话本身的向量召回（mem0/Letta）和时间感知的图谱记忆（Zep/Graphiti），适合超长周期个性化场景——供应链助手的记忆重点是**业务实体状态**（库存/订单），我的 DB 长期记忆本来就存这个，所以没有为了追概念上图谱记忆。"

**5. 一句话带过的：** A2A（agent 间协作协议）在我单系统内用不上但知道它解决什么；语音/computer-use 与供应链场景不搭；Agent 安全上我有白名单+SQL 网关+写闸门+沙箱演进路线（见"能力边界"）。

## 常见追问清单

- ReAct 怎么防死循环？ `MAX_ITERS=6` + 已调去重 + reflector 判停。
- 审批接口怎么防绕过？ API 层 `hasRole("ADMIN")`（不是前端藏按钮）+ 状态机 CAS（`UPDATE ... WHERE status=当前值`，并发/重复请求只有一个成功）+ 终态不可变 + 已生效单禁止物理删除；审批人/时间落库留痕。测试 `purchaseStatusChangeRequiresAdmin` 用 OPS 账号与匿名请求双向验证 403。
- 到货之后库存怎么变？ RECEIVED 状态机分支自动按采购明细入库（入该 SKU 主仓）并写 `inventory_flow` 流水，与手动调整同一套账，同一事务内原子完成。
- interrupt/resume 和我在消息层做确认有什么区别？ interrupt 挂起的是**图执行状态**（含已取证的上下文），恢复后从断点继续、不用重跑；消息层确认只是重发一遍消息，多轮场景状态全靠历史拼接。
- 向量维度不一致？ 建表与 VECTOR_DIMENSIONS 均为 1024（本地 Ollama qwen3-embedding:0.6b）；换维度需同步三处（建表列宽、VECTOR_DIMENSIONS、Mock 向量）并重建索引。
- 为什么选 pgvector？ 单机与业务库同实例运维低，量大可平滑切 Milvus，接口一致。
- 前后端怎么联调？ Vite proxy `/api` 到 8080，JWT 存 localStorage，Axios 统一拦截。
- HITL 怎么体现？ 深度模式：图内 `interrupt()` 挂起 + 前端批准/拒绝 + `Command(resume)` 恢复（thread_id 关联）；java-direct 模式：写意图 `needConfirm` + `confirmCreate` 兜底；执行层 DRAFT→APPROVED 人审。可现场演示"先拦截后确认"。
- 沙箱？ 见"能力边界"一节，背熟。
- 限流在 nginx 反代后怎么取客户端 IP？ 我踩过这个坑：`trust-proxy=false` 时所有请求共享 nginx 容器 IP 一个桶（全体用户互相锁死）；直接开 `trust-proxy=true` 又因为 nginx 用 append 模式的 `X-Forwarded-For`，取第一段等于信任客户端可伪造的头——限流可被刷穿。正确做法：单一可信代理下 nginx **覆写** `X-Forwarded-For $remote_addr`，后端再开 trust-proxy，两端配套才成立（compose 里成对配置）。
- 举一个最有含金量的排查故事？ **"成功路径不释放的幂等键拦下了 HITL 恢复执行"**：跑跨进程恢复演练时，批准后的写操作被幂等服务拒成"重复提交"，但库里根本没有这单。排查链：Langfuse trace 确认恢复链路本身正常（挂起→恢复→写 span 都在）→ 对 Java 侧幂等键在 Redis 里 `TTL` 反推设置时刻 → 与时间线对齐，发现是我自己几分钟前跑的**后端测试**留下的 key——测试连的是共享 dev Redis（成功路径按设计不释放，防 10 分钟内重复创建），测试隔离缺失污染了真实环境。修复：测试改独立参数 + 前置/收尾清理。这个故事同时覆盖：幂等语义设计（"同一业务结果不重复创建"≠"同一意图只能试一次"，所以失败要释放、成功不释放）、分布式排查方法论（先确认链路哪段、再用 TTL/时间线反推）、测试环境隔离意识。

---

## 结束语

> "总结：这个项目证明两件事——第一我能交付可维护的企业级全栈；第二我能把 Agent 做成可执行、可观测、可评估的生产力，而不只是聊天框。"
