# 技术名词总表（零基础向）

> 目标读者：刚接手本项目、对 Web/AI 工程术语还不熟悉的人。每个名词给"初步概念"：**它是什么 + 在本项目里干什么**。按层次分组，依赖清单以 `frontend/package.json`、`backend/pom.xml`、`agent-python/requirements.txt` 为准，无缺漏。运行环境归属（哪个组件跑在哪台机器上）见 [runtime-topology.md](runtime-topology.md)。

## A. 编程语言与文件格式

| 名词 | 概念 |
| --- | --- |
| Java | 后端主语言。写一次编译成字节码，由 JVM 运行；本项目后端所有业务逻辑都是 Java |
| TypeScript | JavaScript + 类型系统。写代码时标明变量类型，编译成 JS 运行；前端语言 |
| JavaScript | 浏览器里唯一原生运行的编程语言；TypeScript 的最终产物 |
| Python | 边车（深度推理服务）的语言，AI 生态最丰富 |
| SQL | 操作关系数据库的标准语言（SELECT/INSERT/UPDATE...） |
| YAML | 缩进式配置文件格式。docker-compose.yml、CI 工作流、application.yml 都用它 |
| JSON | 通用数据交换格式，接口传参/返回的主流格式 |
| JSONL | 每行一个 JSON 的文件格式。评测数据集（golden_rag.jsonl）用它，方便逐条追加/读取 |
| XML | Maven 的 pom.xml 用的老牌标记语言 |

## B. 前端技术栈（浏览器里跑的）

| 名词 | 概念 |
| --- | --- |
| Vue 3 | 前端框架。把页面拆成一个个"组件"，数据变了界面自动更新 |
| Vite | 前端开发服务器 + 打包器。`npm run dev` 起的就是它（:3000），改代码浏览器秒级热更新 |
| Node.js | 让 JS 能脱离浏览器、在操作系统里运行的环境。Vite/Playwright 都靠它 |
| npm | JS 的包管理器（装依赖、跑脚本），对应 Java 的 Maven。`npm ci` = 按锁文件精确安装 |
| Element Plus | Vue 的 UI 组件库：表格、表单、弹窗、下拉框……管理界面的"积木" |
| ECharts (vue-echarts) | 图表库。库存趋势、成本聚合、评测报告的图都是它画的 |
| Pinia | Vue 官方的"全局状态"管理。典型用途：存当前登录用户/角色，全页面共享 |
| Vue Router | 前端路由。URL 变了显示不同"页面"，实际是单页应用内的组件切换 |
| axios | 前端发 HTTP 请求的库。本项目在它上面加了拦截器：自动带 JWT、统一错误处理 |
| marked | 把 Markdown 文本渲染成 HTML。Agent 回复的排版靠它 |
| vue-tsc | 对 .vue 文件做 TypeScript 类型检查，构建前跑一遍防低级错误 |
| ESLint (eslint-plugin-vue) | 代码风格/低级错误静态检查。`npm run lint` |
| jsdom | 在 Node 里模拟的一个"假浏览器"，让组件测试不真开浏览器 |
| Vitest | 跑前端单元测试的框架（Vite 亲兄弟，配置零成本）。`npm test` |
| @vue/test-utils | Vue 官方的组件测试工具库，配合 Vitest 使用 |

## C. 后端技术栈（Java 世界）

| 名词 | 概念 |
| --- | --- |
| Spring Boot | Java 后端框架的事实标准。自动装配 + 内嵌 Web 服务器，一个 `main` 起整个系统 |
| Tomcat | 处理 HTTP 请求的 Web 服务器。Spring Boot 把它内嵌进来，所以 `mvn spring-boot:run` 就能直接访问 :8080 |
| Maven | Java 的构建/依赖管理工具。pom.xml 声明依赖，`mvn spring-boot:run`、`mvn test` 都由它驱动 |
| Spring AI | Spring 生态里对接大模型的框架。本项目用它调 Chat 模型、Embedding、pgvector 向量库 |
| Spring Security | 认证（你是谁）+ 授权（你能干啥）框架。JWT 过滤器挂在它上面 |
| JWT (jjwt) | JSON Web Token：登录后发的一张"加密签名通行证"，之后每个请求带着它证明身份。jjwt 是生成/解析它的 Java 库 |
| Spring Data Redis | 官方封装的 Redis 客户端。Agent 的会话记忆（多轮对话历史）存 Redis |
| JdbcTemplate | 直接写 SQL 操作 PG 的轻量方式（不引入重型 ORM，SQL 全部显式可见） |
| HikariCP | 数据库连接池：预先建好一池子数据库连接反复复用，避免每个请求都握手 |
| Flyway | 数据库表结构版本管理：迁移脚本按版本号排队执行，任何环境启动都得到一致的表结构。本项目规定"Flyway 是 schema 单一事实源" |
| Spring Actuator | 运维端点：/actuator/health（健康检查）、/metrics、/prometheus（指标） |
| Micrometer + Prometheus 格式 | 指标门面，把延迟/计数等指标按 Prometheus 文本格式暴露，可被监控系统抓取 |
| Knife4j (OpenAPI/Swagger) | 自动生成的接口文档界面：http://localhost:8080/doc.html |
| Lombok | 编译期自动生成 getter/setter 等样板代码，让 Java 类干净 |
| jtokkit | 本地计算"这段文本是多少个 token"的库（OpenAI cl100k_base 口径），用于成本台账估算 |
| Apache Tika | 从各类文档里提取纯文本的库（知识库上传的 docx/pdf 解析入口） |
| Logback + MDC | 日志框架 + 日志上下文。MDC 让同一个请求的所有日志自动带上同一个 traceId |
| Bean Validation | 参数校验注解（@NotNull/@Size...），进 Controller 就拦截非法参数 |
| H2 | 纯 Java 的轻量数据库，可跑在内存里。测试/无 Docker 演示用它，数据不落盘 |
| JUnit 5 (Jupiter) | Java 测试框架：@Test 注解的方法就是一个测试 |
| Mockito / AssertJ / MockMvc | 测试三件套（随 spring-boot-starter-test）：Mockito 假装依赖对象、AssertJ 流式断言、MockMvc 模拟 HTTP 请求测 Controller |

## D. 数据库与存储

| 名词 | 概念 |
| --- | --- |
| PostgreSQL (PG) | 主流开源关系数据库。本项目的唯一业务数据真源 |
| pgvector | PG 的向量扩展：给表加一种"向量列"，支持按语义相似度检索。**所谓"向量数据库"就是它，不是独立软件** |
| 余弦距离 (cosine distance) | 衡量两个向量的方向是否接近，越接近越相似。pgvector 检索用它算分 |
| HNSW | 向量索引算法（分层可导航小世界图），让百万级向量检索也能毫秒级返回 |
| Redis | 内存键值数据库，读写极快。本项目存 Agent 会话记忆（多轮上下文） |
| Docker 卷 (volume) | Docker 的持久化硬盘：容器删了，卷里的数据还在（agent_pgdata/agent_redisdata） |
| 事务 (Transaction) | 一组数据库操作要么全成功要么全回滚。采购单入库+写流水就是同一事务 |
| 连接池 | 见 HikariCP |
| Schema 迁移 | 表结构变更也走"版本提交"（Flyway 管），避免手工改表导致环境不一致 |

## E. Docker 与运行环境

| 名词 | 概念 |
| --- | --- |
| Docker | 把应用连同其运行环境打包成"容器"运行的工具。容器≈轻量级小虚拟机 |
| 镜像 (image) | 容器的"安装光盘"（只读模板），如 pgvector/pgvector:pg16 |
| 容器 (container) | 镜像的一次运行实例，可启停可删 |
| Docker Compose | 用一个 yml 清单一键编排多个容器（本项目：PG+Redis+可选 Langfuse/边车） |
| 端口映射 (-p) | 把容器端口发布到宿主机：`5432:5432` 表示连宿主机 5432 = 连容器里 5432 |
| Dockerfile | 制作镜像的"配方"（backend/agent-python/frontend 各有一份） |
| healthcheck | compose 里的健康检查命令，确认容器"真的能服务了"才放行依赖它的服务 |
| profiles | compose 的可选服务分组（obs=Langfuse，deep=边车容器），不指定就不启动 |
| nginx | 高性能 Web 服务器/反向代理。生产部署时前端页面由 nginx 提供，再把 /api 转发给后端 |
| WSL2 | Windows 官方的 Linux 子系统。Docker Desktop 在里面跑一个专属发行版 `docker-desktop` |
| 环境变量 / .env | 不改代码注入配置的方式（数据库地址、API Key、开关）。.env 本地私有，不入库 |
| Spring Profile | 同一套代码按环境切换配置（default=本地 Docker；vmware=连虚拟机；prod=生产） |
| conda | Python 虚拟环境管理器。边车开发环境在你机器的 D:\conda_envs\ai-backend |

## F. 网络与协议

| 名词 | 概念 |
| --- | --- |
| HTTP | 浏览器与服务器之间的通信协议。"请求-响应"一来一回 |
| REST / API | 接口设计风格：URL 表示资源，GET 查/POST 建/PUT 改/DELETE 删。前后端就靠它通信 |
| SSE (Server-Sent Events) | 服务器单向持续推送的 HTTP 流。Agent 的"打字机效果"就是它（流式回复、深度推理事件流） |
| WebSocket | 双向长连接（聊天室常用）。本项目未用——SSE 够用且更简单，列出来是防混淆 |
| CORS | 浏览器安全策略：跨域请求需服务端白名单放行（后端配置 allowed-origins） |
| localhost / 127.0.0.1 | "本机自己"的地址。后端配置里连的就是它 |
| 端口 | 一台机器上区分不同服务的编号（8080=后端，5432=PG……） |
| host.docker.internal | 容器里访问"宿主机"的特殊域名（k6 压测容器用它打本机后端） |
| 命名管道 (named pipe) | Windows 上进程间通信机制，Docker Desktop 内部组件用它说话（了解即可） |

## G. AI / Agent 概念（本项目的主角）

| 名词 | 概念 |
| --- | --- |
| LLM | 大语言模型（如 GPT、通义千问）。本项目默认接 opencode 网关的 mimo-v2.5 |
| Token | 模型的计费/计数单位，约等于一个词或半个汉字。所有成本核算围绕它 |
| OpenAI 兼容接口 | 行业事实标准的模型 API 格式。换模型供应商只需换 base-url+key |
| Embedding（嵌入） | 把文本变成一串数字（向量），语义相近的文本向量也相近。本机 Ollama 跑 qwen3-embedding |
| RAG | 检索增强生成：先从知识库里检索相关片段，再让模型基于片段回答——既准又能给引用 |
| 召回 (recall/top-k) | RAG 第一阶段：按向量相似度取 top-k 候选片段 |
| 重排 (rerank) | RAG 第二阶段：用更强的算法给候选重排序。本项目用 BM25（真 IDF 词频饱和+长度归一） |
| BM25 | 经典的关键词相关性排序算法（向量检索的"另一只眼"，关键词命中它更敏感） |
| ChatMemory | 对话记忆：多轮对话时把历史也发给模型。本项目存 Redis |
| Function Calling / 工具调用 | 模型输出"我要调 X 工具+参数"，由代码执行后把结果喂回模型。Agent 取数的核心机制 |
| @Tool (Spring AI) | Java 侧声明工具的注解。库存/合同/采购等 7 个只读工具由它暴露给模型 |
| MCP (Model Context Protocol) | 把工具按开放协议暴露的标准（本项目边车把同一批工具按 MCP 提供，stdio 传输） |
| Prompt / 系统提示词 | 发给模型的指令文本。提示词有版本管理表，可激活/回滚 |
| Prompt 注入 & 消毒 | 用户在输入里夹带"忽略之前指令"类攻击。PromptGuard 负责消毒检测，评测集里有注入题 |
| NL2SQL | 自然语言转 SQL 查询。有白名单校验（禁改数据/多语句/系统表）防止越权 |
| HITL (Human-in-the-loop) | 人在回路：Agent 要执行写操作（创建采购单）前必须人工批准。图内 interrupt 实现 |
| LangGraph | Python 的"状态图"编排框架：planner→tools→reflect→reasoner 一个节点接一个节点推进 |
| Checkpointer | LangGraph 的状态存档器。配 CHECKPOINT_URI 后挂起的审批落 PG，重启可恢复 |
| interrupt / resume | 图内挂起/恢复机制：跑到写工具前停下等人批准，拿到结果继续 |
| Mock vs Real | 无 Key 时用假模型完整演示（响应可甄别 provider=mock）；有 Key 一键切真模型 |
| 熔断/降级 | 边车不可用时自动切回 Java 链路，不阻塞业务；失败如实标注 DEGRADED 不伪装 |
| 流式输出 | 模型边生成边吐 token，前端逐字显示。HTTP 层面即 SSE |
| 双维评测 | ①LLM-as-judge 评答案质量；②轨迹评测评工具调用序列是否合理（见 §H） |

## H. 测试与质量（E2E/CI 的主场）

| 名词 | 概念 |
| --- | --- |
| 单元测试 | 测一个函数/类，依赖全用假的（Mockito）。快、数量多 |
| 集成测试 | 起接近真实的组件（H2/真 SQL）测模块间配合 |
| E2E 测试 (端到端) | 把整个系统真起来（前端+后端+PG+Redis），模拟真人从浏览器一路点到结果。Playwright 干这个 |
| Playwright | 浏览器自动化测试框架：能开真 Chromium、点击/输入/断言页面内容。`npm run e2e` |
| 冒烟测试 (smoke) | 快速验证主流程没坏的"点验"，不求全覆盖。k6-smoke.js 是压测冒烟 |
| 压测 (load test) | 模拟大量并发请求看系统扛不扛得住。k6 用"虚拟用户"爬坡到 50 并发 |
| VU / ramping | k6 的虚拟用户数 / 从少到多的爬坡过程 |
| P95 | 95% 的请求都比这个数快。压测报延迟的标准口径（P50/P95/P99） |
| 断言 (assert) | 测试里的自动判分：assertEqual(期望, 实际)，不满足即测试失败 |
| golden 数据集 | 预先人工整理的"标准问答对/标准工具序列"，作为评测基准（60+14 条） |
| LLM-as-judge | 用另一个（或同个）大模型给回答打分。本项目评 faithfulness（忠于原文）/relevance（切题），0-2 分 |
| 轨迹评测 | 不只看答案对不对，看"调用了哪些工具、顺序对不对"：precision（别乱调）/recall（该调的调了）/F1（二者调和平均） |
| precision / recall / F1 | 准确率（说对的比例）/召回率（该说的说全的比例）/两者的调和平均。搜索与分类的通用指标 |
| 回归 (regression) | 改了 A 功能把 B 功能弄坏的失误。回归测试=防止这个 |
| 门禁 (gate) | CI 里的数值红线：评测指标不达标（如 f1<0.9）直接判失败，禁止合并 |
| 覆盖率 | 测试覆盖了多少代码行/分支（项目未以此做门禁，概念了解） |
| pytest | Python 的测试框架（边车测试） |
| Vitest | 前端测试框架（见 §B） |

## I. 工程协作与交付

| 名词 | 概念 |
| --- | --- |
| Git | 版本管理：每次提交(commit)存一个快照，可回溯/分支/合并 |
| GitHub | 托管 Git 仓库的云端平台 |
| CI/CD | 持续集成/持续交付：每次推送代码，机器自动跑 测试→构建，绿了才算合格 |
| GitHub Actions | GitHub 内置的 CI 引擎。`.github/workflows/ci.yml` 定义"什么时候、在什么机器、跑哪些步骤" |
| workflow / job / step / runner | Actions 的四层结构：工作流→作业→步骤→执行机器（GitHub 的临时虚拟机） |
| artifact (制品) | CI 跑完留下的产物文件（如 playwright-report 测试报告） |
| lint | 静态检查：不运行代码，只扫风格错误和低级 bug（ESLint） |
| 构建 (build) | 把源码变成可运行产物的过程：Java→jar，前端→dist 静态文件 |
| 部署 (deploy) | 把构建产物放到能被访问的地方跑起来（docker-compose.prod.yml 是生产部署清单） |
| 多阶段构建 | Dockerfile 技巧：先用带编译器的镜像构建，再用干净小镜像只装产物 |

## J. 安全与治理

| 名词 | 概念 |
| --- | --- |
| 认证 vs 授权 | 认证=你是谁（登录）；授权=你能干什么（权限） |
| RBAC | 基于角色的权限控制。本项目 ADMIN（全权）/OPS（只读）两角色 |
| 限流 (rate limit) | 每用户每分钟最多几次请求，防刷防雪崩（令牌桶算法，可配置开关） |
| 幂等 (idempotency) | 同一操作重复提交结果不变。防"双击创建两张采购单" |
| 状态机 | 状态只能按预定义路径流转（DRAFT→APPROVED→RECEIVED），CAS（比对再更新）防并发乱序 |
| 审计留痕 | 关键操作记录"谁、何时、干了什么"（审批人/时间落库） |
| fail-closed | 出错时宁可拒绝也不放行（分类器故障时写意图按强动作词处理） |
| 成本台账 | 每次模型调用的 token 用量×单价记账，真实/估算双口径，绝不把估算伪装成真实 |
| 可观测性三支柱 | 日志（log）、指标（metrics）、链路（trace）。出问题能回答"发生了什么/哪里慢/谁调的谁" |
| Trace / TraceId | 一次请求的全程追踪。入口生成 ID，所有日志/子调用带着它串起来 |
| Langfuse | LLM 专用观测平台：一次 Agent 运行=一条 trace，含每次工具调用(span)和模型调用(generation)与真实 token 用量 |
| trace / span / generation | Langfuse 术语：trace=一次运行全链路；span=一次工具取证；generation=一次模型调用（带用量） |

## K. 项目自有组合词（文档里高频出现）

| 名词 | 概念 |
| --- | --- |
| 边车 (sidecar) | 旁挂在主服务旁边的辅助服务。本项目的 Python 深度推理服务就叫边车 |
| java-direct | 不走边车、由 Java 直接调用模型的链路（降级兜底/轻量请求） |
| 写闸门 | 写操作执行前的强制人工确认机制（关键词快路径 + LLM 分类器兜底） |
| 三条链路 | 非流式对话 / 流式对话(SSE) / 深度推理(边车图)，见 code-map.md |
| 知识库 (knowledge) | 上传文档→解析(Tika)→切片→向量化→pgvector，供 RAG 检索 |
| 采购审批链 | DRAFT→(ADMIN 批准)→APPROVED→(收货)→RECEIVED 自动入库+流水 |
| HITL 恢复 (resume) | 前端批准后带 Command(resume) + 同 thread_id 让图继续跑 |
| headless 初始化 | Langfuse 首次启动自动建组织/项目/密钥，无需手工注册 |
| 一键重放 | test-report.md §8 的脚本序列，可在新机器复现所有测试数字 |
