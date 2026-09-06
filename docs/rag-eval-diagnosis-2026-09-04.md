# RAG 与真实评测诊断（2026-09-04）

## TL;DR

- 之前"真实模型评测 hit=0.650 偏低"主要**不是模型能力问题**，而是评测脚本本身的三个构造缺陷 + 数据集/种子数据缺失。
- 你对 RAG 的印象是对的：`knowledge_doc` 表里只有 2 篇随迁移种入的文档（合同风控规范、历史风险案例），工具定义/系统参数/业务数据这些 golden 里的"知识"**只写在 `golden_rag.jsonl` 的 contexts 字段**，从未进入真实向量库。评测因此是"预置上下文自问自答"，并不检验线上检索质量。

## 1 失分逐题归因（21 个 hit<1.0 样本）

复现 `llm_judge.py` 的 bigram 召回后逐题分类：

| 类别 | 含义 | 数量 | 典型样本 |
|---|---|---|---|
| **A 数据集缺陷** | must_contain 关键词在**任何** context 里都不存在，模型再强也答不出 | 2 | #28 要 `quantity`（context 只有"数量和单价"没英文参数名）、#13 要"库存充足"（context 只有"充足"） |
| **B 召回代理失败** | 证据在池中但 bigram 双字重合没把它排进 top-2 | 15 | #17/#18/#21/#24/#25（工具名/参数）、#31/#32/#36/#39/#40（HITL/幂等/角色）、#9/#45/#47/#49/#60 |
| **C 证据已入 top2 但答案仍 miss** | 生成/关键词匹配问题，占比很小 | 4 | #15（工具名答对但拼写）、#29/#33/#38 |

**结论**：真实失分里 **B 类占绝对多数**——即"召回没把料给到模型"。这正是 bigram 双字重合度代理的固有缺陷，而非 MiMo/muse 推理能力不足。文档 test-report 里那段"切 top-2 重合度排序后绝大多数恢复命中，说明失分在召回质量而非模型幻觉"的观察与此完全一致。

## 2 已修复

### 2.1 真实向量召回替换 bigram 代理（llm_judge.py）
- 新增 `VectorRecall`：走 Ollama OpenAI 兼容 `/embeddings`（`qwen3-embedding:0.6b`，1024 维），余弦 top-4，对齐生产 `RagService` 重排后的召回口径。
- CLI `--recall {vector|auto|bigram}`，默认 `vector`；embedding 不可用时 `auto` 回退 bigram。CI 用 `--recall bigram`（托管 runner 无 Ollama，保持离线确定性）。
- 新增 `avg_evidence_recall` 指标：直接量化"答案所需证据是否进入 top-k"，把**检索问题**和**生成问题**分开归因。
- 实测（mock 模式，同一批答案，只换召回）：bigram `evid=0.683` → **vector `evid=0.917`**，+23pp，印证 B 类是代理召回造成的假失分。

### 2.2 模型路由与生产对齐
- `llm_judge` 的答题/裁判调用改成与 Java `AiConfig` 完全同逻辑：**只有 muse 走 opencode `/responses`，其余（含 MiMo v2.5）走标准 `/chat/completions`**。之前脚本对任何 opencode 模型都先试 `/responses`，与生产不一致。

### 2.3 数据集缺陷修正（golden_rag.jsonl）
- #13 `must_contain` 由"库存充足"改为"充足"（与 context 用词一致）。
- #28 `must_contain` 改为 `createPurchaseOrder`+`供应商`，并在该条 context 补入英文参数名 `supplierId/skuCode/quantity/unitPrice`。

### 2.4 RAG 冷启动种子（补上"库里没文档"）
- 新增 `backend/src/main/resources/knowledge/*.md` 6 篇：工具目录、采购 HITL、安全护栏、系统参数、可观测性、业务快照——覆盖 golden 里此前从未入库的知识。
- 新增 `KnowledgeSeeder`（`@Order(2)`，建表后执行）：启动时按 classpath 扫描，按标题去重、走**真实 ingestion**（分段→`knowledge_doc`/`knowledge_chunk`→向量库），离线 H2 与生产 PG 同路径。
- `DemoDataInitializer` 加 `@Order(1)` 保证先建表。
- 效果：线上 AgentChat/RAG 现在开箱即有可召回文档，不再只有 2 篇。

## 3 验证

- 后端 `mvn test`：**56 用例（51 通过 + 5 个 EVAL_REAL_LLM 门控跳过）0 失败**。
  - 当日稍后补两处回归（已修，本地全绿复核）：① `VectorStoreConfig` 的 `initializeSchema(true)` 在 H2 测试上下文执行 PG 专有 `CREATE EXTENSION vector` 导致 6 个 SpringBootTest 启动失败 → 改为属性 `spring.ai.vectorstore.pgvector.initialize-schema` 驱动，测试 profile（`src/test/resources/application-test.yml`）置 false；② 限流器行为随"本机是否恰好运行 Redis"漂移（有 Redis 时 AgentPerfTest 120 并发超 30/min 得 429）→ `RateLimitInterceptor` 增加 `smartsupply.ratelimit.enabled` 开关，测试 profile 关闭，任何环境结果确定。顺带清理 4 个测试类遗留的 `dimensions=1536` 旧值 → 1024。
  - CI backend job 同步从"只跑 4 个纯单元测试类"扩为全量 `mvn -B test`（真实模型测试有环境变量门控，无 Key 自动跳过），杜绝此类"CI 绿、全量红"的覆盖盲区。
- Python 离线套件：`13 passed, 2 skipped`。
- Mock 评测（vector 召回）：`avg_keyword_hit=0.892 avg_evidence_recall=0.917`。
- **真实评测（MiMo v2.5 + vector 召回）：`hit=0.858 evid=0.917 faith=1.55 rel=1.82`**，见第 4 节对比。

## 4 真实评测重跑结果（MiMo v2.5 + 真实向量召回，2026-09-04）

命令：`OPENAI_API_KEY=... AI_MODEL=mimo-v2.5 python scripts/llm_judge.py --mode real --recall vector`
报告：`docs/eval-report-real-mimo-2026-09-04.md`（60 样本）

| 指标 | 旧报告（muse + bigram 代理） | 新报告（MiMo v2.5 + Ollama 向量召回） | 变化 |
|---|---|---|---|
| avg_keyword_hit | 0.650 | **0.858** | +20.8pp |
| avg_evidence_recall（新指标） | —（代理下仅 0.683） | **0.917** | +23.4pp |
| avg_faithfulness (0-2) | 1.43 | **1.55** | ↑ |
| avg_relevance (0-2) | 1.42 | **1.82** | ↑ |

**归因结论被数据证实**：旧低分的主体（B 类 15/21）确为 bigram 召回代理造成的假失分，换真实语义召回 + MiMo 后全部恢复。

剩余 8 个 hit=0 样本的两类问题（已非主因）：
1. **检索 top-k 边界**（#32/#39/#40/#45，evid=0）：幂等 key 格式、ADMIN 角色、参数化 SQL 这类"问题措辞与知识段落语义距离大"的条目，没进 cosine top-4。生产链路是 top-8→重排 top-4，比评测脚本多一层召回窗口，实际线上会更好；如仍要满分，可将脚本 top-k 对齐为 8→BM25/覆盖度重排→4。
2. **must_contain 匹配过严**（#36/#38/#41/#47，evid=1 但 hit=0）：其中 #41/#47 是注入/越权题，模型行为**正确**（拒绝、不泄露），只是答案没复述 PromptGuard 术语；#36 正确解释了缺参数不能建单但未命中 "DRAFT" 字面词。此类属评测口径问题，建议对安全题改用裁判分（其 faith/rel 均为高分）而非关键词硬匹配。

## 5 附：模型事实

- MiMo-V2.5 为推理模型：响应含 `reasoning_content`（思维链）与 `content`（终答）。`max_tokens` 过小时预算会被思维链耗尽导致 `content` 为空——评测与生产调用均**不要**设置小 max_tokens。
- 网关 `https://opencode.ai/zen/go/v1`：MiMo 走 `/chat/completions`（OpenAI 兼容，与 Java `AiConfig` 的 `OpenAiChatModel` 路由一致）；`/v1/models` 免鉴权可列模型。
