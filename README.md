# SmartSupply — Agent 赋能的智能供应链协同平台（C 路：企业底座 + 原创 Agent）

> 大厂主流：**Java 业务编排 + Python LangGraph 深度推理 + Vue3 前端**。无 Key 可跑，有 Key 一键切真实模型。

## 架构

```
Vue3 (frontend, Vite proxy /api -> 8080)
  │ REST / SSE  POST /api/agent/chat  POST /api/agent/chat/stream
  ▼
Spring Boot 3.5 + Spring AI  Java 编排层 (backend)
  ├─ 业务域：supplier / product / sku / warehouse / inventory / purchase / contract / bi / stats
  ├─ Agent：ChatClient + @Tool(7) + RAG(pgvector 1024 HNSW, citations) + ChatMemory(Redis, LLM摘要) + SSE + 反馈
  ├─ 安全：Spring Security + JWT + 全局异常 + 限流(Redis) + SQL 参数化
  ├─ 观测：agent_run/step/tool_call/user_feedback + TraceId跨语言 + jTokkit计费
  ├─ 治理：/api/admin/agent/{runs,costs,prompts,eval} + Vue 4页
  └─ 委托：useDeep/agentType=deep 时经 PythonSidecarService -> LangGraph 边车
         ▲ 回调 Tool API（inventory/supplier 等只读）   │
         │                                              ▼
       Python 边车 (agent-python, 复用 D:\conda_envs\ai-backend)
         FastAPI + LangGraph(规划->工具->反思) + Mock/真实 LLM(OpenAI 兼容)
基础设施：PostgreSQL + pgvector + Redis + MinIO（docker-compose）
```

**开关：** 默认纯 Java 可跑；深度推理需同时满足：

1. 启动边车：`D:\conda_envs\ai-backend\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload`（或 `agent-python/start.bat`）
2. Java 设 `AGENT_PYTHON_ENABLED=true`（`application.yml` / 环境变量 / `docker-compose.prod.yml`），前端“深度推理”开关才可用。

## 目录

- `backend/` Java 17 + Spring Boot 3.5.14 + Spring AI 1.0.0 + JdbcTemplate + pgvector
- `frontend/` Vue 3.4 + TS 5.5 + Vite 5 + Element Plus + ECharts（已 code-split，`npm run build` 产出 `dist/`）
- `agent-python/` FastAPI + LangGraph 边车（`D:\conda_envs\ai-backend` 已装 langchain/langgraph/fastapi/openai/httpx）
- `backend/src/main/resources/db/migration/` **Flyway 迁移 = schema 单一事实源**（V1 建表+演示数据 supplier/warehouse/product/sku/inventory/contract/knowledge_doc，V2+ 加固），compose/VM 初始化都执行这同一套脚本
- `docker-compose.yml` 开发一键起（postgres/redis/minio）
- `docker-compose.prod.yml` 生产一键起（+ backend + agent-python，`SPRING_PROFILES_ACTIVE=prod`）
- `docs/` 架构与 3 分钟面试剧本

## 一键启动

### 你的环境（VMware 直连，已配置 192.168.10.100，Windows 不再起任何数据库）

```bash
# 1) VMware 里（与你现有 MySQL/Redis 共存，新增 PG 向量库）
docker run -d --name smartsupply-postgres -p 5432:5432 -v pgdata:/var/lib/postgresql/data \
  -e POSTGRES_DB=smartsupply -e POSTGRES_USER=dev -e POSTGRES_PASSWORD='change-me-strong-password' \
  pgvector/pgvector:pg16
# 初始化（Flyway 迁移目录是唯一 schema 源；把 D:/Agent/backend/src/main/resources/db/migration 整目录拷到虚拟机后按序执行，或直接跑 vm-setup/install-pgvector.sh）
scp -r D:/Agent/backend/src/main/resources/db/migration dev@192.168.10.100:/tmp/migrations
for f in $(ls /tmp/migrations/*.sql | sort -V); do PGPASSWORD='change-me-strong-password' psql -h 127.0.0.1 -U dev -d smartsupply -f "$f"; done  # 也可直接重跑 vm-setup/install-pgvector.sh
# 确认 Redis 密码与端口放行（你已设 change-me-strong-password）
redis-cli -h 127.0.0.1 -a 'change-me-strong-password' ping  # 无用户名，仅密码；应返回 PONG
# 按需放行防火墙
sudo firewall-cmd --permanent --add-port=5432/tcp --add-port=6379/tcp --add-port=9000/tcp && sudo firewall-cmd --reload

# 2) Windows（直连 VMware，不执行 docker-compose.yml）
cd D:/Agent/backend
set SPRING_PROFILES_ACTIVE=vmware
mvn spring-boot:run
# 或一次性覆盖：set SPRING_DATA_REDIS_PASSWORD=change-me-strong-password && mvn spring-boot:run -Dspring-boot.run.profiles=vmware

# 可选深度推理
D:\conda_envs\ai-backend\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
# 前端另起终端
cd D:/Agent/frontend && npm install && npm run dev
# 前端 http://localhost:3000  账号 admin / admin123
# 后端 http://localhost:8080  文档 /doc.html  健康 /actuator/health
# 边车 http://127.0.0.1:8001  健康 /health  推理 POST /api/reason
# 自检：Windows 侧执行
# powershell -Command "Test-NetConnection 192.168.10.100 -Port 5432,6379"
# curl http://192.168.10.100:5432  应建连；redis-cli -h 192.168.10.100 -a 'change-me-strong-password' ping
```

### 面试官演示（Windows 本地 docker-compose 一键起，仅备用）

```bash
docker-compose up -d          # 起 pgvector + redis + minio（你本地不需要，仅给面试官）
cd D:/Agent/backend && mvn spring-boot:run  # 默认连 localhost
```

### 生产（VMware/云服务器同理，用 .env 外置）

```bash
copy .env.example .env  # 复制后填入你的真实凭据（.env 已被 .gitignore 排除，切勿提交明文）
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
# 前端：npm run build 产出 dist/，由 Nginx/对象存储托管
```

## 模型接入（AiConfig 装配规则）

- **离线演示**（默认 `AI_MOCK=true`）：`MockChatModel` + `MockEmbeddingModel`，Tool/RAG/SSE 流式链路全部可演示，不依赖任何 Key。
- **真实 Chat 模型**（`AI_MOCK=false` + `OPENAI_API_KEY`）：任一 OpenAI 兼容端点自动走标准 `OpenAiChatModel`（原生 Tool Calling + 流式）——OpenAI / DeepSeek / 通义千问 compatible-mode / 智谱 / vLLM 均可，改 `OPENAI_BASE_URL` + `AI_MODEL` 即切换；muse 网关自动走 `/responses` 协议的 `MuseSparkChatModel`（含真流式 stream() 实现）。
- **真实 Embedding**（与 Chat 解耦，`EMBEDDING_*` 独立配置）：默认对接**本地 Ollama**（免费、离线、中文友好）——`ollama pull qwen3-embedding:0.6b`（1024 维）后设 `EMBEDDING_BASE_URL=http://localhost:11434/v1`、`EMBEDDING_API_KEY=ollama`、`EMBEDDING_MODEL=qwen3-embedding:0.6b`、`VECTOR_DIMENSIONS=1024` 即可；中文语义区分度实测 gap≈0.47（相关对 0.67 vs 无关对 0.20）。也可指向 OpenAI text-embedding-3-small（1536 维，需同步 `VECTOR_DIMENSIONS=1536` 与建表列宽）；DeepSeek/opencode 网关无 embeddings 接口。无任何端点时回退 `MockEmbeddingModel`（哈希伪向量，检索无语义，仅保证链路可演示），可用 `EMBEDDING_MOCK` 强制。
- 评测：`golden_rag.jsonl` 60 条 + `test_agent_eval` + `scripts/llm_judge.py`（faithfulness/relevance 0-2），产出 `docs/eval-report-*.md`（mock 基线 0.90）；CI mock 门禁阻断，真实模型本地跑。
- 工具链安全：7 个工具全部经 `ToolSecurity`（读=可追溯、写=登录+ADMIN 角色校验），请求级工具调用追踪随 `/api/agent/chat` 返回 `tools` 字段、SSE `done` 事件回传，前端 AgentChat 以 chips + markdown 渲染。

## 前端双通道

- **流式**：`POST /api/agent/chat/stream` SseEmitter 打字机
- **深度推理**：`useDeep=true` 且 `GET /api/agent/mode` 返回 `pythonSidecarEnabled=true` 时走边车，否则回落 Java 直连；未就绪时开关自动禁用

## 企业级能力

- 持久化观测：四表落库 + 异步线程池 + X-Trace-Id 跨语言回放 + ThreadLocal 隔离串号 + jTokkit 真实分词
- 可信评测：60 条 golden + 端到端 Agent 评测 + LLM-as-judge + CI 门禁 + 两组报告
- RAG 可信化：citations[docId/title/score] + [n] 强制补全 + 前端卡片 + 赞踩闭环
- 治理后台：runs/costs/prompts/eval 4页 + ADMIN 守卫

## 企业级加固（本次 20% 补齐）

- `application-prod.yml` + `logback-spring.xml`：prod 日志 INFO + 滚动文件 + 健康检查
- 全局异常：`GlobalExceptionHandler` 区分 400/403/429/500，prod 隐藏堆栈
- 安全：JWT 校验 + `SecurityConfig` 放行白名单 + `RateLimit`/`RateLimitInterceptor`（Redis 计数，429 限流）+ 全量 SQL 参数化（`?` 占位，`ILIKE ?`）
- 限流示例：`POST /api/agent/chat` 30/min、`POST /api/bi/analyze` 20/min；前端 `request.ts` 对 401 自动清 token 跳登录，429/403 友好提示
- 部署：`backend/Dockerfile`（JRE 17）、`agent-python/Dockerfile`（python:3.11-slim）、`docker-compose.prod.yml` 健康依赖与环境变量外置、`.env.example`

## 验证

```bash
cd D:/Agent/backend && D:/tools/Maven/bin/mvn package -DskipTests  # 已产出 81M fat jar
cd D:/Agent/frontend && npm run build                                # 已产出 dist/ 2247 modules
```

> 约束：Maven 仓库 `D:\tools\maven-repository`、npm 缓存 `D:\npm-cache` / 全局 `D:\tools\npm-global`、Python `D:\conda_envs\ai-backend`，均已落盘 D 盘未侵占 C 盘。

## 演示剧本

见 `docs/interview-script.md`（合同风控 RAG / 补货可执行 / NL2SQL 安全三段），`docs/architecture.md` 为分层与时序。
