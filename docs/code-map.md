# 源码导读（Code Map）

从源码复习项目时按本文顺序读：先看链路骨架，再看每层的实现文件。所有路径相对仓库根。

## 0. 一图流：一次深度对话怎么走

```
浏览器
  frontend/src/api/index.ts (axios + JWT, request.ts 拦截器)
  frontend/src/components/AgentChat.vue (输入/流式开关/confirm UX/轨迹面板)
    │ REST 或 SSE
    ▼
backend AgentController (/api/agent/chat, /chat/stream)
    │  鉴权(JwtAuthFilter) → 输入消毒(PromptGuard) → 会话权限(ChatMemoryService)
    │  写意图 → java-direct 层关键词闸门（深度模式跳过，由图内 interrupt 接管）
    ├─ java-direct 路径: Spring AI ChatClient + @Tool 工具自主调度
    └─ 深度路径: PythonSidecarService (超时/重试/熔断, 透传用户 JWT)
         ▼
       agent-python/app/main.py (/api/reason, /api/reason/stream)
         graph.py: planner → tools → reflector → reasoner
           tools.py 回环调 Java 只读 API（带同一用户 JWT, ok=false 信封）
           写工具执行前 interrupt() 挂起 → confirm_required → Command(resume) 恢复
           → Java 执行层 PurchaseOrderWriter 落库（ADMIN/幂等/DRAFT）
    │
    ▼
  响应流回前端；ObservationService 异步落 agent_run/agent_step/agent_tool_call
  可选重轨: observability.py → Langfuse trace/span/generation
```

## 1. 后端 Agent 层（复习重点）

`backend/src/main/java/com/smartsupply/agent/`

| 文件 | 复习要点 |
| --- | --- |
| `AgentController.java` | 对话总入口：非流式/流式(SSE)/深度开关/写意图闸门/会话权限校验。先读它——整条链路的"总开关"。SSE 响应带 `X-Accel-Buffering: no`（反代缓冲踩坑见 lessons-learned #7） |
| `tools/InventoryTools.java` 等 | @Tool 工具集（Catalog/Contract/Inventory/Purchase），LLM function-calling 的取数面 |
| `tools/ToolSecurity.java` | 工具级鉴权切面 |
| `tools/SqlValidator.java` | NL2SQL 白名单（禁 DML/多语句/系统表），只读语义的执行层兜底 |
| `tools/PurchaseOrderWriter.java` | 写操作可信执行层：ADMIN 鉴权随 JWT 裁决/幂等/独立事务/DRAFT，created_by 解析 sys_user.id（lesson #4） |
| `rag/RagService.java` | 向量召回 topK=8 → 双轨重排 top4 + ILIKE 兜底 → 引用强制 |
| `rag/Reranker.java` | 真 BM25（k1=1.2/b=0.75，IDF 在候选集局部统计，口径见类注释） |
| `rag/CrossEncoderReranker.java` | 调 Python `/api/rag/rerank`，熔断回退 BM25 |
| `memory/ChatMemoryService.java` | Redis 7 天/40 条 + 超限压缩摘要 + DB 恢复回写 |
| `PythonSidecarService.java` | RestClient 超时/指数退避/熔断；JWT 透传（回环身份）；SSE 逐事件消费转发（JDK HttpClient 显式 HTTP/1.1） |
| `ObservationService.java` | 计数器 + 异步线程池落库，观测失败仅告警不拖垮主链路 |
| `PromptGuard.java` | 注入消毒 + `<knowledge>/<user_query>` 标签隔离 |
| `IdempotencyService.java` | 幂等键 TTL 10min，成功路径按设计不释放（lesson #6 的主角） |
| `ReplenishmentScheduler.java` | 定时补货预测入口（Agent 的定时触发面） |

common/：`TraceIdFilter` + `TraceContext`（跨 Java/Python/SSE 线程 trace）、`RateLimitInterceptor`、`TokenContext`（并发计费隔离）、`CurrentUser`。

## 2. 配置层

`backend/src/main/java/com/smartsupply/config/`

- `AiConfig.java` — 模型装配与 AI_MOCK 切换
- `MockChatModel.java` / `MuseSparkChatModel.java` — 离线/真实双 provider；真实侧解析 usage 回填 actual，无 usage 走 estimated（与边车 `usage_or_estimate` 同一诚实性原则）
- `PromptRegistry.java` — prompt 版本化 + 回滚
- `SecurityConfig.java` / `JwtAuthFilter.java` — 认证授权
- `VectorStoreConfig.java` — pgvector HNSW
- `KnowledgeSeeder.java` — RAG 冷启动种子（"库里没文档"缺陷的修复，见 rag-eval-diagnosis）
- `DemoDataInitializer.java` — 演示数据

## 3. Python 边车

`agent-python/app/`

| 文件 | 复习要点 |
| --- | --- |
| `main.py` | FastAPI 入口：`/api/reason`、`/api/reason/stream`(SSE)、`/api/rag/recall`、`/api/rag/rerank`；lifespan 收尾 checkpointer 连接 |
| `graph.py` | LangGraph 状态图：planner → tools(并行取证) → reflector → reasoner；`MAX_ITERS=6`；写工具节点 interrupt() 写闸门；planner/reflector/reasoner 的 usage 埋点 |
| `tools.py` | Java 工具回环客户端——一切失败返回 ok=false 信封，不编造数据 |
| `checkpointing.py` | checkpointer 工厂：缺省 MemorySaver；`CHECKPOINT_URI` → AsyncPostgresSaver（autocommit 连接池 + setup 迁移）；Postgres 不可达诚实降级 + 告警（lesson #3） |
| `llm.py` | provider 抽象（真实/mock）、`estimate_tokens` + `usage_or_estimate`（actual 优先 / estimated 打标） |
| `observability.py` | Langfuse 封装层：trace/span/generation，SDK 2.x↔v2 Server 版本矩阵注释（lesson #1）；未配置零开销；埋点全 try/except |
| `mcp_server.py` | MCP 服务器（stdio）：7 只读工具 + 写工具需 `MCP_ENABLE_WRITE=1`——人工批准不可外包给传输协议 |
| `rerank.py` | cross-encoder 重排实现 |

跨进程恢复演练脚本：`scripts/hitl_restart_drill.py`（suspend/resume 两个独立进程，lesson #4 的验收证据）；`scripts/gen_langfuse_traces.py` 一键生成演示 trace；`scripts/eval_trajectory.py` + `tests/golden_trajectory.jsonl` 轨迹评测。

## 4. 前端

`frontend/src/`

- `components/AgentChat.vue` — Agent 交互主体：流式开关、写闸门 confirm 二次确认 UX、"查看推理轨迹"面板
- `utils/sse.ts` — SSE 增量解析器（纯函数、有单测 `utils/__tests__/sse.spec.ts`）：多行 data 拼接/空行事件边界/注释行忽略
- `api/index.ts` + `utils/request.ts` — API 封装与 JWT 拦截器
- `views/AgentWorkspace.vue` 等 — 业务页面；`views/admin/`（Runs/Costs/Prompts/Eval）治理后台四页，ADMIN 路由守卫

## 5. 数据库

`backend/src/main/resources/db/migration/` — Flyway 为 Schema 单一事实源：
V1 init → V2 agent_hardening → V3 agent_observability → V4 po_approval_trace（approver/approved_at 留痕）。H2 schema（`schema-h2-demo.sql`、test `schema-h2.sql`）仅为本地/测试镜像——方言漂移的教训见 lessons-learned #4。

## 6. 测试地图（"报告里的数字从哪来"）

| 层 | 位置 | 说明 |
| --- | --- | --- |
| Java 单测/集成 | `backend/src/test/java/com/smartsupply/` | H2 快测；`BusinessFlowTest`（含 agent 写采购真实落库）；`PostgresRegressionTest`（Testcontainers pgvector:pg16 + 真实 Flyway 迁移，无 Docker 自动跳过） |
| Python 评测与单元 | `agent-python/tests/` | golden 60 条评测、轨迹评测 14 条、checkpointer/MCP/观测/HITL 恢复 |
| 前端 E2E | `frontend/e2e/main-flow.spec.ts`（`npm run e2e`） | 登录 → 库存 → Agent 写意图 → confirm 闭环 |
| 负载 | `deploy/loadtest/k6-smoke.js` | 读链路 50 VU 冒烟，口径（关限流）见脚本注释 |
| CI | `.github/workflows/ci.yml` | 单测 + mock 评测数值门禁 + e2e job（起栈打 jar 跑 Playwright） |

一键重放命令见 [test-report.md §8](test-report.md)。
