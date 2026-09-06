# 文档导航 — 从源码复习 SmartSupply

本页是全部文档的总索引：先说清每份文档是什么、什么状态，再给三条按需选择的复习路径。

## 文档地图

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [README.md](README.md)（本页） | 索引与复习路径 | — |
| [architecture.md](architecture.md) | 分层、三条关键链路时序、配置切换、记忆、可观测，末尾附**设计决策记录**（每个"为什么"） | 最新 |
| [code-map.md](code-map.md) | **源码导读**：请求全链路图 + 每个模块读哪些文件、按什么顺序 | 最新 |
| [lessons-learned.md](lessons-learned.md) | **开发问题复盘**：16 个真实问题，现象→排查→根因→修复→教训 | 最新（持续追加） |
| [test-report.md](test-report.md) | 工程测试与量化报告（面试 STAR 数字，§8 一键重放） | 最新 |
| [agent-ops.md](agent-ops.md) | 观测/评测/治理能力清单 + 按时间的演进日志（能力契约） | 最新 |
| [interview-script.md](interview-script.md) | 面试演示剧本、前沿对标话术、追问清单 | 最新 |
| [rag-eval-diagnosis-2026-09-04.md](rag-eval-diagnosis-2026-09-04.md) | RAG 逐题失分归因、修复与验证（RAG 质量的主线文档） | 2026-09-04 |
| [eval-report-real-summary.md](eval-report-real-summary.md) | 真实模型 RAG 评测汇总（脱敏公开版） | 2026-09-05 |
| [eval-report-mock-2026-09-04.md](eval-report-mock-2026-09-04.md) | mock 评测报告（CI 门禁同口径） | 快照 |
| [eval-report-mock-2026-09-02.md](eval-report-mock-2026-09-02.md) | mock 评测报告（更早口径） | 历史快照 |
| [rag-eval.md](rag-eval.md) | RAG 链路与成本首轮记录 | 历史快照（主线见 diagnosis） |

## 复习路径

### 路径一：30 分钟捡起全局
1. 根 [README](../README.md) —— 架构图 + 核心能力（能背出五条能力线）
2. [architecture.md](architecture.md) —— 三条链路时序 + 末尾设计决策记录
3. [test-report.md](test-report.md) §1 汇总 —— 记住关键数字（测试量、评测指标、压测 P95）

### 路径二：从源码深入（半天）
按 [code-map.md](code-map.md) 的顺序走：
1. §0 请求全链路图（建立空间感）
2. §1 后端 Agent 层 → §3 Python 边车（两层的对应关系：闸门/工具/观测各有一条对角线）
3. 读代码时配合 [architecture.md](architecture.md) 的链路时序对号入座
4. 遇到"这里为什么这样写"→ 查 [lessons-learned.md](lessons-learned.md) 对应条目（每个坑都标了涉及源码）

### 路径三：按主题
| 主题 | 阅读顺序 |
| --- | --- |
| 架构与设计权衡 | architecture.md（设计决策记录一节是核心） |
| 踩坑与排查能力 | lessons-learned.md 全文，⭐ 三条优先 |
| RAG 质量 | rag-eval-diagnosis → eval-report-real-summary |
| 评测体系 | test-report.md（答案质量+轨迹双维）→ agent-ops.md 评测节 |
| 面试表达 | interview-script.md + test-report.md §7 STAR 话术 |
