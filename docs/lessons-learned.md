# 开发问题复盘（Lessons Learned）

记录项目全程真实遇到并修复的问题：**现象 → 排查 → 根因 → 修复 → 教训**。标 ⭐ 的是有完整排查链条的"金牌叙事"，面试被问"遇到过什么难的问题"从这里取材。配套代码位置见 [code-map.md](code-map.md)。

## 速查表

| # | 问题 | 一句话教训 |
| --- | --- | --- |
| 1 | Langfuse SDK/Server 版本错配 | 可观测中间件先查版本矩阵，别装最新 SDK 配旧服务端 |
| 2 | Windows 事件循环 vs psycopg | 平台默认事件循环也是依赖项 |
| 3 | CONCURRENTLY 跑进事务块 | 绕过官方工厂手工建连接池，要把官方隐含参数当契约抄 |
| 4 ⭐ | created_by 类型 bug（68 绿测漏掉） | 绿色测试只证明"跑过的路径"是对的；方言差异要用真引擎回归 |
| 5 | compose initdb 嵌套只读挂载 | 整目录挂载与单文件挂载混用是 Docker 反模式 |
| 6 ⭐ | 幂等键污染共享 Redis | 测试隔离含"副作用服务"键空间；先证明链路对，再查谁动了环境 |
| 7 ⭐ | vite 代理缓冲毫秒级 SSE | 反代默认行为是 SSE 隐形杀手，`X-Accel-Buffering: no` 是显式契约 |
| 8 | Playwright 浏览器下载挂起 | 国内网络 CI/工具链要显式配镜像源 |
| 9 ⭐ | Langfuse generation usage 全空 | 观测缺口用"打标估算"补，估算不能伪装成真实用量 |
| 10 | 边车回环 403 被静默吞成空结果 | "降级为空"是最危险的失败模式 |
| 11 | 审批 RBAC 缺失 | "人审"叙事必须在 API 层成立，藏按钮不是防线 |
| 12 | nginx XFF 可伪造刷穿限流 | 限流键与代理拓扑强耦合，要一起设计 |
| 13 | bm25Like 没有 IDF | 简历上每个名词都要经得起"展开讲" |
| 14 | mock 秒回定出的超时预算全失效 | 按时延假设定的预算，换真模型即作废 |
| 15 | k6 脚本头层级错误 100% 失败 | 压测先 1 VU 冒烟再上量 |
| 16 | 关键词 if/else 伪装规划 | 评测的前提是链路真实性，trace 要能区分 mock 与真模型 |

---

## A. 依赖与环境

### 1. Langfuse SDK/Server 版本错配
- **现象**：实跑生成 trace 时 `auth_check` 直接 ValidationError——SDK 3.15.0 期望 Server v3 的 projects 响应结构（含 organization/metadata），自部署 v2 服务端不满足。
- **根因**：Langfuse 存在硬版本矩阵——SDK 3.x 只配 OTel 原生的 Server v3（需 ClickHouse/MinIO/Redis）；单容器 v2 服务端只配 SDK 2.x 经典 API。装最新 SDK ≠ 兼容自部署镜像。
- **修复**：SDK 降 2.60.10，`observability.py` 重写为经典 API。注意 2.x 构造函数没有 `tracing_enabled` 参数，传了会 TypeError 导致观测被静默永久关闭；封装层把这些版本差异全部吃掉，调用方零改动。
- **教训**：升级路径（v2→v3）写进版本矩阵注释，演进只动封装层一个文件。

### 2. Windows 默认事件循环与 psycopg 异步不兼容
- **现象**：脚本里 AsyncPostgresSaver / psycopg 连接报事件循环不兼容。
- **根因**：Windows 默认 ProactorEventLoop 不支持 psycopg 需要的 socket API。
- **修复**：脚本入口 `asyncio.set_event_loop_policy(WindowsSelectorEventLoopPolicy())`；uvicorn 侧同此处理。
- **教训**：跨平台异步库把"事件循环类型"当成隐式依赖。

### 3. CREATE INDEX CONCURRENTLY 跑进事务块
- **现象**：手工建 `AsyncConnectionPool` 后跑 checkpointer `setup()` 迁移报错。
- **根因**：CONCURRENTLY 语法禁止在事务块内执行；官方 `from_conn_string` 内部隐含 `autocommit=True, prepare_threshold=0, row_factory=dict_row`，手工建池没带这些参数。
- **修复**：池 kwargs 显式对齐官方工厂的全部隐含参数。
- **教训**：绕开官方工厂手工构造基础设施时，官方工厂的隐含参数就是契约。

### 5. compose initdb 嵌套只读挂载
- **现象**：postgres 容器启动失败 `read-only file system`。
- **根因**：把单文件 `999-create-langfuse-db.sh` 挂进整目录只读挂载的 `/docker-entrypoint-initdb.d`——Docker 需要在只读挂载内创建挂载点，必然失败。
- **修复**：整目录挂载 `deploy/postgres-init`；schema 单一事实源保持在应用内 Flyway，initdb 只管辅助库。
- **教训**：同一个挂载点"整目录 + 单文件"混用是 Docker 反模式。

### 8. Playwright chromium 下载挂起
- **现象**：官方 CDN 下载 30 分钟停在 55KB。
- **修复**：`PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright` 后 45 秒完成。
- **教训**：国内网络的开发/CI 环境，二进制分发一律显式镜像源。

---

## B. 数据与持久化

### 4 ⭐. created_by 类型 bug：68 个绿测为什么没抓住
- **现象**：agent 写采购单在真实 PG 必报错——BIGINT 列收到用户名字符串；但当时 68 个测试全部通过。
- **根因**：该路径此前只有 Python 侧打桩测试，Java 侧从未真实落库；全部测试跑在 H2 上，与 PG 的类型/方言检查存在漂移。
- **修复**：`PurchaseOrderWriter` 事务内解析 `sys_user.id`（缺失不阻断，用户名留 remark 审计）；新增 `PostgresRegressionTest`（Testcontainers pgvector:pg16 + 真实 Flyway V1→V4 迁移）把"真 PG"纳入回归；无 Docker 自动跳过。
- **教训**：测试绿 ≠ 路径被覆盖。方言/类型类 bug 的解法不是多写 H2 用例，而是引入真引擎回归层。这是"测试金字塔缺一层"的完整案例。

---

## C. 测试隔离与排查

### 6 ⭐. 幂等键污染共享 Redis（排查链最完整的一条）
- **现象**：HITL 恢复演练被"重复提交"拦截，初步像是边车恢复逻辑 bug。
- **排查**：Langfuse trace 证明恢复链路本身正常 → 对 Redis 幂等 key 做 TTL 反推写入时刻 → 与测试运行时间线对齐 → 锁定是测试留下的 key。
- **根因**：幂等服务成功路径**按设计不释放 key**（防重复提交，TTL 10min）；业务流测试的参数与演练撞车，且共用开发 Redis。
- **修复**：测试改独立参数（quantity=42）+ 前置/收尾清理键空间。
- **教训**：①测试隔离不止是数据隔离，还包括幂等/限流这类"副作用服务"的键空间；②排查顺序应当先证明"链路对不对"，再查"谁动了环境"——trace 是第一现场。

---

## D. SSE 与前端链路

### 7 ⭐. vite dev 代理缓冲"毫秒级完成"的 SSE（E2E 卡三天级排查）
- **现象**：E2E 里写闸门 confirm 按钮永不出现；后端日志确认事件已发送；curl 直连正常；前端直连也正常。
- **排查**：逐层二分（后端→代理→前端）后找到规律：应用**第一次**流式请求确定性挂死，数秒后重放秒过——vite dev 代理（http-proxy）对"毫秒级发送+complete"的 SSE 快路径会缓冲首事件直到超时。
- **修复**（三件套）：① confirm 事件改异步线程发送（与主链路同线程模型）；② `/api/agent/chat/stream` 响应补 `X-Accel-Buffering: no`（ResponseEntity 头）——nginx 生产环境同样会缓冲小体积 SSE 事件，此头显式关闭；③ E2E 走非流式确定性路径（写闸门逻辑两路共用，覆盖不受损）。
- **教训**：SSE 问题的排查面是"后端→代理→前端"三段；反代对 SSE 的默认缓冲行为是隐形杀手，显式响应头是唯一可移植的契约。

### 15. k6 压测 100% 失败
- **现象**：压测全红，误以为后端撑不住。
- **根因**：压测脚本头合并写错层级——`Content-Type` 落在请求顶层而非 `headers` 内，后端 `HttpMessageNotReadableException` 400。
- **教训**：压测先 1 VU 冒烟验证全绿再上量，否则产出的数字全部作废。

---

## E. 可观测与诚实性

### 9 ⭐. Langfuse generation 的 token usage 全为空
- **现象**：真实 trace 里所有 generation 的 usage 字段为空。
- **根因**（双因）：① planner/reflector 调用点压根没传 usage；② provider 网关不回传 usage 时无兜底。
- **修复**：`llm.usage_or_estimate`——provider 真实回传（source=actual）优先；缺失时本地 CJK 感知粗估（source=estimated）**只进观测层**，Java 成本台账仍走 jtokkit；observability 双格式落库（`usage` ModelUsage 供 v2 服务端解析 + `usageDetails` 供 v3 前向兼容）；来源随 `metadata.token_source` 落 trace。
- **教训**：遥测数据同样适用诚实性原则——用"打标估算"补缺口，绝不让估算伪装成真实用量。

### 10. 边车回环 403 被静默吞成空结果
- **现象**：边车调 Java 只读工具拿到空数据，无任何报错，看起来像"库里没数据"。
- **根因**：依赖未文档化的 `JAVA_JWT_TOKEN` 服务账号，缺失时回环 403 被静默吞掉转成空结果。
- **修复**：`/chat` 透传发起用户 JWT 作为回环身份（审计归属真实用户），服务账号仅兜底；回环失败以 `auth_failed` 显式带出，不伪装成空数据。
- **教训**：agent 系统里最危险的失败模式是"静默降级为空"——模型会拿空数据编造答案。

### 16. 关键词 if/else 伪装规划
- **现象**：早期"planner"是关键词规则硬编码，trace 上看不出真假。
- **修复**：planner/reflector 改 function-calling + 参数 schema 校验（缺必填/坏类型即丢弃并记录）；关键词规则只保留在 Mock provider 且 trace 标 `provider=mock`。
- **教训**：评测的前提是链路真实性——trace 必须能区分真模型与 mock，否则评测数字不可信。

---

## F. 安全与正确性

### 11. 审批 RBAC 缺失
- **现象**：任何登录用户可把 DRAFT 采购单直接改 APPROVED——"人工审批"话术在 API 层不成立（前端藏按钮不构成防线）。
- **修复**：`PUT /api/purchase-orders-extra/{id}/status` 强制 ADMIN；配套 V4 迁移加 approver/approved_at 留痕、CAS 状态机（`WHERE status=当前值`）、已生效单禁止物理删除。
- **教训**：安全叙事的每一环都要落到服务端强制，而非 UI 引导。

### 12. nginx X-Forwarded-For 可伪造 → 限流刷穿
- **根因**：append 模式（`$proxy_add_x_forwarded_for`）首段是客户端可伪造的；`trust-proxy` 开启时限流键取首段即可任意伪造刷穿。
- **修复**：nginx 改覆写 `X-Forwarded-For=$remote_addr`（单一可信代理语义）；prod 显式 `SMARTSUPPLY_RATELIMIT_TRUST_PROXY=true`——不开则全体用户共享 nginx IP 一个限流桶互相锁死（反方向的双向坑）。
- **教训**：限流键、代理拓扑、信任模型三者强耦合，必须一起设计。

### 13. bm25Like 没有 IDF
- **现象**：重排器是"无 IDF 的词频启发式"，面试被追问 IDF 即穿帮。
- **修复**：重写为真 BM25（k1=1.2/b=0.75，词频饱和+长度归一），IDF 在 top-8 候选集局部统计并在类注释如实标注口径；中文分词统一 CJK 双字对齐。
- **教训**：简历/文档上的每个名词都要经得起"展开讲"；如实标注近似口径（局部 IDF）比假装精确更经得住追问。

---

## G. 弹性与超时

### 14. mock 秒回定出的超时预算，对真模型全部失效
- **现象**：90s 读超时以 `"extracting response"` 截断形态失败，且不走重试判定。
- **根因**：真实推理模型单次 1~4 分钟（curl 实测 46s，多步 141~150s），预算全按 mock 时延定。
- **修复**：分级校准——边车 blocking-timeout-ms=300s、流式 request 600s、SSE emitter 深度 600s/普通 120s、openai SDK 显式 240s。
- **教训**：一切按时延假设设计的预算，换模型即作废；预算必须按真实时延实测校准，且失败形态（截断 vs 超时异常）要分别覆盖。
