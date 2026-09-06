# RAG 回归说明（2026-08-27）

## 链路

- 切片：`TextSplitter 800/100 滑动窗口，中文句号“。”友好切分`
- 入库：`RagService.ingest -> knowledge_doc + knowledge_chunk + VectorStore.add(归一化伪向量，历史记录为 1536 维，现全项目统一 1024)`，H2 下 `currval` 退化为 `MAX(id) WHERE title=?`
- 召回：`RagService vector top8(阈值0.2) -> 双轨重排(CrossEncoderReranker auto: Python cross-encoder -> BM25回退) top4` + `ILIKE ? 参数化兜底3条` + 向量命中时混合补齐1条；`RecallDetail{vectorHits, reranked, latencyMs}` + `rag.rerank.count{mode}` 可观测，`RAG_RERANK_MODE` 可切换 `auto|bm25|cross-encoder`
- 重排：`Reranker.java` 离线 BM25 + `CrossEncoderReranker.java` 调 `agent-python /api/rag/rerank`（`rerank.py` cross-encoder/ms-marco，缺权重自动回退 BM25，熔断 5次/30s）
- 防注入：`ILIKE ?` 占位 + `SqlValidator` 复用表白名单，`RagServiceTest` 含 `' OR 1=1 -- ; DROP` 回归
- Python：`chromadb` 内存向量往返、`sentence-transformers`、`ragas/datasets` 就绪，`/api/rag/recall` 召回占位 + `/api/rag/rerank` 双轨重排 + `golden_rag.jsonl 20条` 离线评估
- 新增评测：`test_tool_accuracy.py(5)` 测工具选型/注入消毒/标签隔离/引用/HITL，`AgentGuardTest(7)` 测 PromptGuard/HITL/引用/Token

## 成本与延迟实采
- `MuseSparkChatModel` 解析 `/responses usage` 回填 `actual`，无 usage 则 `estimated`；`AgentController` 经 `consumeLast*` 对账并打 `agent.chat.tokens{source}` / `cost_usd{source}` 指标
- 流式首字：`AgentController.stream()` 首个 `token` 事件计时 `agent.chat.ttft`，`done` 事件回传 `{ttfbMs,totalMs,tokenSource}`，面试可 `curl -N /api/agent/chat/stream` 现场看
- 聚合：`GET /api/agent/metrics/summary` 拉 `latency/ttft/rerank/tool` 均值与计数，`GET /actuator/prometheus` 抓取

## 本地验证

```bash
cd backend && mvn -o test -Dtest=RagServiceTest,AgentGuardTest -f pom.xml  # 13 passed
cd agent-python && python -m pytest tests/test_golden_eval.py tests/test_tool_accuracy.py -v  # 11 passed
```
