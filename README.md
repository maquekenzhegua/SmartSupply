# SmartSupply — Agent 赋能的智能供应链协同平台

企业级全栈 + 原创 Agent。Java 负责业务编排与治理，Python LangGraph 负责深度推理，Vue 3 负责协作前端。无 Key 可完整演示，有 Key 一键切换真实模型。

## 核心能力

- **业务闭环** — 供应商、商品、库存、采购、合同、BI，覆盖主流程，统一鉴权与限流
- **可信 Agent** — 7 个工具调用、pgvector RAG 引用、流式输出、写入操作需人工确认
- **深度推理** — LangGraph 规划-工具-反思状态图，边车不可用时自动回退 Java 链路
- **观测与治理** — Trace 全链路、成本与时延指标、Admin 后台与评测门禁

## 架构

```
Vue 3 前端  ── REST / SSE ──►  Spring Boot 3.5 编排层
                                业务域 · Agent (RAG/Tool/Memory) · 安全 · 观测
                                    │
                                    │  需要深度推理时
                                    ▼
                              Python 边车 (FastAPI + LangGraph)
                                规划 → 工具 → 反思 → 推理

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

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 启动后端
cd backend && mvn spring-boot:run

# 3. 启动前端
cd frontend && npm install && npm run dev
# 前端 http://localhost:3000  后端 http://localhost:8080/doc.html

# 可选：深度推理边车
python -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
# 前端开启"深度推理"开关需同时设置 AGENT_PYTHON_ENABLED=true
```

演示账号 admin / admin123 仅用于本地演示，生产环境请通过环境变量覆盖。

## 配置

| 场景 | 配置 |
| --- | --- |
| 离线演示（默认） | 无需 Key，开箱即用 |
| 真实模型 | 设置 OPENAI_API_KEY、OPENAI_BASE_URL、AI_MODEL 自动切换 OpenAI / DeepSeek / 通义千问等兼容接口 |
| 向量模型 | 默认对接本地 Ollama，也可指向 OpenAI Embedding |

生产部署参考 docker-compose.prod.yml，通过 .env 注入凭据，详见 .env.example。

## 目录

- backend — Java 服务与 Flyway 迁移
- frontend — Vue 前端
- agent-python — LangGraph 边车
- docs — 架构说明与演示剧本

## 文档

- 架构与时序：docs/architecture.md
- 演示剧本：docs/interview-script.md

## License

MIT
