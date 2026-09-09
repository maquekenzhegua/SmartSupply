# SmartSupply — Agent 赋能的智能供应链协同平台

企业级全栈 + 原创 Agent。Java 负责业务编排与治理，Python LangGraph 负责深度推理，Vue 3 负责协作前端。无 Key 可完整演示，有 Key 一键切换真实模型。

## 核心能力

- **业务闭环** — 供应商、商品、库存、采购、合同、BI，覆盖主流程；采购审批 ADMIN RBAC + 状态机 CAS + 审批留痕，RECEIVED 自动入库记流水；统一鉴权与限流
- **可信 Agent** — 8 个工具（7 只读 + 1 个 HITL 写操作）、pgvector RAG 引用、LangGraph `interrupt()` 挂起人工批准 + `Command(resume)` 恢复，执行层 ADMIN 鉴权/幂等/DRAFT 审批；工具同时按 **MCP 开放协议**暴露（写工具默认不暴露，人工批准不可外包给传输协议）
- **深度推理** — LangGraph 规划-并行取证-写闸门-反思状态图，checkpointer 可插拔（缺省进程内 MemorySaver；`CHECKPOINT_URI` 配 Postgres 后挂起的审批跨重启/多实例可恢复），provider 原生 token 级流式，边车不可用时自动回退 Java 链路
- **观测与治理** — Trace 全链路 + Langfuse 可选重轨（一次运行一条 trace，含工具 span 与 reasoner generation 真实用量）、真实/估算 token 双口径成本台账、Admin 后台与数值型评测门禁
- **双维评测** — 答案质量 LLM-as-judge（faithfulness/relevance）+ 工具轨迹评测（golden 期望序列 vs 实际执行序列，precision/recall/f1），均进 CI 数值门禁

## 架构

```
Vue 3 前端  ── REST / SSE ──►  Spring Boot 3.5 编排层
                                业务域 · Agent (RAG/Tool/Memory) · 安全 · 观测
                                    │
                                    │  需要深度推理时
                                    ▼
                              Python 边车 (FastAPI + LangGraph)
                                规划 → 工具 → [写闸门 interrupt/人工批准] → 反思 → 推理
                                                        │ (可选)
                                                        ▼
                                                  Langfuse Trace

基础设施: PostgreSQL + pgvector + Redis  ·  Flyway 为 Schema 单一来源
```

## 技术栈

| 分层 | 选型 |
| --- | --- |
| 前端 | Vue 3.4 + TypeScript 5.5 + Vite 5 + Element Plus + ECharts |
| 后端 | Java 17 + Spring Boot 3.5 + Spring AI + JdbcTemplate + Spring Security JWT |
| Agent | pgvector 1024 HNSW + RAG 重排 + ChatMemory (Redis) + SSE |
| 边车 | FastAPI + LangGraph + OpenAI 兼容接口 |
| 基础设施 | PostgreSQL + pgvector + Redis · Docker Compose |

## 快速开始

一键启停（推荐，脚本内置健康门禁：Docker 冷启动等待、Postgres 就绪、Ollama 模型预热、
各组件端口/HTTP 探活，任一步失败自动打印对应日志尾部并中止）：

```bat
:: Windows：双击或命令行运行（首次会自动拉起 Docker Desktop 并等待引擎就绪）
start-all.cmd      rem 启动全栈：docker(postgres/redis/langfuse) + ollama + 边车 + 后端 + 前端
status-all.cmd     rem 查看各组件健康状态与端口
stop-all.cmd       rem 全部停止（容器移除、数据卷保留，下次启动数据还在）
```

等效的手工步骤（Linux/macOS 或需要分步排查时）：

```bash
# 1. 启动基础设施（langfuse 为可选 profile）
# 生产为组合编排（obs 服务密钥可强制必填）：见下
# 本地开发：
docker compose --profile obs up -d

# 2. 启动深度推理边车（Windows 必须用 run_sidecar.py 启动器：
#    psycopg 异步池不兼容 Windows 默认 Proactor 事件循环，直接 uvicorn 会静默降级 MemorySaver）
cd agent-python && python run_sidecar.py      # http://localhost:8001/health

# 3. 启动后端（数据源凭据需与 docker-compose 的 postgres 一致）
cd backend && SPRING_DATASOURCE_USERNAME=dev SPRING_DATASOURCE_PASSWORD=change-me-strong-password mvn spring-boot:run

# 4. 启动前端（3000 被 langfuse 占用，dev 端口固定为 3001，/api 已代理到 8080）
cd frontend && npm install && npm run dev
# 前端 http://localhost:3001  后端 http://localhost:8080
```

演示账号 admin / admin123（ADMIN）与 ops / ops123（只读角色，可演示写操作被 RBAC 拒绝）仅用于本地演示；生产环境请通过 DEMO_ADMIN_PASSWORD / DEMO_OPS_PASSWORD 环境变量覆盖。

```bash
# 浏览器级 E2E 主链路（需 postgres/redis 容器 + 后端 8080 在跑）
cd frontend && npm run e2e

# 读链路负载冒烟（后端限流需临时关闭：压数据库而非限流器）
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < deploy/loadtest/k6-smoke.js
```

## 配置

| 场景 | 配置 |
| --- | --- |
| 离线演示（默认） | 无需 Key，开箱即用 |
| 真实模型 | 设置 OPENAI_API_KEY、OPENAI_BASE_URL、AI_MODEL 自动切换 OpenAI / DeepSeek / 通义千问等兼容接口 |
| 向量模型 | 默认对接本地 Ollama，也可指向 OpenAI Embedding |
| 观测（可选） | 本地开发 `docker compose --profile obs up -d`；生产 `docker compose -f docker-compose.prod.yml -f docker-compose.obs.yml up -d`（Prometheus/Grafana/Alertmanager，指标已由 Micrometer 暴露，告警规则在 deploy/observability/）。Langfuse trace：`python agent-python/scripts/gen_langfuse_traces.py` 一键生成演示 trace；不配置则完全禁用、零开销 |
| TLS | 默认 80；需要 HTTPS 时叠加 `docker-compose.prod.tls.yml`（证书放 deploy/certs/），三种接入方式见 docs/tls.md |
| checkpointer 持久化（可选） | 边车配 `CHECKPOINT_URI`（Postgres）后 interrupt 挂起状态落库，重启/多实例仍可恢复待审批写操作；缺省进程内 MemorySaver |

生产部署参考 docker-compose.prod.yml，通过 .env 注入凭据，详见 .env.example。

## 目录

- backend — Java 服务与 Flyway 迁移
- frontend — Vue 前端
- agent-python — LangGraph 边车
- docs — 架构说明与演示剧本

## 文档

复习从 [docs/README.md](docs/README.md)（文档导航与复习路径）进，推荐顺序：

- 架构与设计决策：docs/architecture.md
- 源码导读：docs/code-map.md
- 开发问题复盘：docs/lessons-learned.md
- 测试与量化报告：docs/test-report.md
- 演示剧本：docs/interview-script.md

## License

MIT
