# 运行拓扑 — 什么在哪台机器上跑

> 回答一个问题：**开发/测评时，每个组件到底跑在哪、连的是谁、数据存在哪**。
> 起因（2026-09-09 复盘）：曾一直以为"数据库在 VMware 虚拟机里"，实际测评全程连的是**本机 Docker 容器**。本文杜绝此类混淆。

## 1. 全景图（Windows 本机视角）

```
┌─ Windows 本机（localhost = 这台机器）──────────────────────────────────┐
│                                                                        │
│  手动启动的进程（关机/关终端即无，不占端口时后端起不来）                    │
│   ├─ 前端 Vite dev server          :3000   (npm run dev)                │
│   ├─ Spring Boot 后端              :8080   (mvn spring-boot:run)        │
│   └─ Python 边车 uvicorn           :8001   (深度推理，可选)               │
│                                                                        │
│  Docker Desktop（WSL2 发行版 docker-desktop；数据实体在 D:\DockerData） │
│   ├─ 容器 smartsupply-postgres     :5432  ← PG16 + pgvector 扩展        │
│   ├─ 容器 smartsupply-redis        :6379                               │
│   ├─ 可选 --profile obs: langfuse  :3000  ⚠ 与前端 dev 端口冲突，二选一   │
│   └─ 可选 --profile deep: agent-python :8001                            │
│                                                                        │
│  本机其他服务                                                           │
│   └─ Ollama                        :11434  ← qwen3-embedding（Embedding）│
│                                                                        │
│  仅出站 HTTPS 的云端                                                     │
│   └─ opencode.ai 网关（.env 的 OPENAI_BASE_URL）— chat 模型 mimo-v2.5   │
└────────────────────────────────────────────────────────────────────────┘

┌─ VMware 虚拟机 "Java-Backend-Server"（备用路径，项目从未使用）───────────┐
│  VM 里另有自己的 Docker（也跑 smartsupply-postgres/redis，还有 MySQL3306）│
│  仅当 SPRING_PROFILES_ACTIVE=vmware 且设 VM_IP 时后端才会连它（见下 §5）  │
└────────────────────────────────────────────────────────────────────────┘

GitHub Actions（CI）：测试在 GitHub 的机器上跑，CI 里同样 docker compose
起 postgres/redis——与本机环境无关。
```

**关键认知三条：**

1. **"向量数据库"不是独立组件** —— 它是 PostgreSQL 里的 pgvector 扩展，`vector_store` 表就在 smartsupply 库里。基础设施总共只有两样：**一个 PG（带 pgvector）+ 一个 Redis**。
2. **后端配置里写的是 `localhost:5432/6379`** —— 谁在监听这两个端口就连谁。Docker Desktop 开着 → 连容器；都没有 → 启动直接失败。配置从不指向 VMware（除非显式激活 vmware profile，从未发生过）。
3. **`docker compose down` 删容器不删数据** —— 数据在 Docker 命名卷里（`agent_pgdata`/`agent_redisdata`），容器列表是空的≠数据没了。

## 2. 端口与数据归属表

| 端口 | 组件 | 跑在哪 | 状态/数据在哪 | 谁连它 |
| --- | --- | --- | --- | --- |
| 3000 | 前端 Vite dev | 本机 node 进程 | 无状态 | 人（浏览器） |
| 8080 | Spring Boot 后端 | 本机 java 进程 | 业务数据落 PG；会话记忆落 Redis | 前端、边车回环、Playwright、k6 |
| 8001 | Python 边车 | 本机 uvicorn（或容器 `--profile deep`） | 无状态；HITL 挂起态经 CHECKPOINT_URI 落 PG | 后端（AGENT_PYTHON_ENABLED=true 时） |
| 5432 | PG16+pgvector | **Docker** smartsupply-postgres | Docker 卷 `agent_pgdata` → 物理在 D 盘 | 后端、边车 checkpointer |
| 6379 | Redis 7 | **Docker** smartsupply-redis | Docker 卷 `agent_redisdata` → 物理在 D 盘 | 后端（ChatMemory 会话记忆） |
| 11434 | Ollama | 本机进程 | 模型文件 | 后端（Embedding，`.env` 指定） |
| 3000 ⚠ | Langfuse（可选） | Docker `--profile obs` | 同一 PG 实例的独立 langfuse 库 | 边车埋点；**与前端 dev 端口冲突** |

Redis 里只有会话记忆（丢 = 对话历史丢，重开即无，不影响业务数据）；**一切业务数据的唯一真源是 PG**（Flyway 为 schema 单一事实源）。

## 3. 测评（eval）到底怎么跑

测评分三层，**离线评测根本不需要真实模型**，真实链路评测才需要全套环境：

| 层 | 跑法 | 依赖 |
| --- | --- | --- |
| 单元/离线门禁 | `mvn test`（test_golden_eval / test_agent_eval） | mock AI，不连模型；PG/Redis 视测试而定 |
| LLM-judge / 轨迹评测 | `python scripts/llm_judge.py` / `scripts/eval_trajectory.py` | 同上（mock 口径），产出 `docs/eval-report-*.md` |
| 真实模型实跑 | 全套环境（见 §4）+ `.env` 真实 Key | **Docker 的 PG/Redis + 本机 Ollama + opencode 网关** |

历史测评报告（eval-report-real-*.md）的产出环境就是上表最后一行：**本机 Docker 容器 + 本地进程**，与 VMware 无关。

## 4. 各场景启动序列

```bash
# ① 日常开发/演示
docker compose up -d postgres redis
cd backend && mvn spring-boot:run          # 读 .env 需先注入环境变量
cd frontend && npm run dev                 # http://localhost:3000

# ② 深度推理（可选）
cd agent-python && python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
# 并设 AGENT_PYTHON_ENABLED=true

# ③ 真实模型评测 = ① + ② + .env（LLM 网关 Key + Ollama 在跑）

# ④ E2E（Playwright）：① 起完后
cd frontend && npm run e2e

# ⑤ 压测（k6）：后端 :8080 在跑的前提下，k6 以容器方式打宿主机
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < deploy/loadtest/k6-smoke.js

# ⑥ 可选观测（Langfuse）：注意 3000 与前端冲突，跑 Langfuse 就别跑前端 dev
docker compose --profile obs up -d langfuse

# ⑦ CI：全部自动，GitHub 机器上 docker compose up -d postgres redis + mvn test + e2e
```

## 5. VMware 备用路径（为什么存在、为什么没被用到）

- `vm-setup/install-pgvector.sh`：在虚拟机/云服务器里**也是用 Docker** 起 pgvector + Redis（VM 里另有 MySQL 3306 共存）。
- `backend/src/main/resources/application-vmware.yml`：让**本机后端**直连 VM 里的库（`SPRING_PROFILES_ACTIVE=vmware` + `VM_IP` 环境变量）。
- 项目所有启动文档/CI/演示剧本走的都是默认 profile（localhost → Docker）。**vmware profile 从未被激活过**（后端日志 `No active profile set` 可证）。
- 结论：这台 VM（D:\VirtualMachines\Java-Backend-Server，约 21GB）当前对项目**无作用**，仅当需要演示"数据库部署在独立服务器"场景时才有意义。

## 6. 本机现状快照（2026-09-09 环境清理后）

- Windows 服务版 PostgreSQL 16：**已卸载**。曾安装但从未被项目使用（未装 pgvector 扩展，建不了向量表——这是判断"测评连的是谁"的决定性证据）。
- Docker 数据实体：`C:\Users\Administrator\AppData\Local\Docker\wsl` 已改为 **junction 指向 `D:\DockerData\wsl`**（同日迁移，真实验证过数据完整：vector_store/2.3 万条 chat_message/Redis 153 键）。WSL 注册表 BasePath 同步指向 D。GUI 里"Disk image location"显示 C 路径属正常，实际字节在 D。
- Docker Desktop 程序本体在 C（约 3.4GB，无法迁移，固定开销）。
- VMware 虚拟机保留未动（21GB，D:\VirtualMachines）。
- 当前机器为冷状态：8080/5432/6379/8001/11434 均无进程监听；要用即按 §4 启动。

## 7. 30 秒自查："现在连的到底是谁"

```bash
netstat -ano | findstr "5432 6379 8080"   # 端口被谁监听（有 PID）
docker ps                                  # 容器在不在跑
wsl -l -v                                  # docker-desktop 发行版状态
```

后端启动日志前三行铁证：`No active profile set`（= 默认 profile = localhost）+ `Database: jdbc:postgresql://localhost:5432/smartsupply` + Flyway 迁移行。若看到 `vmware` profile 才是连虚拟机。
