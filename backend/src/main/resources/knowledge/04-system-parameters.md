# 系统参数与架构配置

文本分段：TextSplitter 采用 800 字窗口、100 字重叠，尽量在句号处切断，适配中文长文。

会话记忆：ChatMemoryService 近期 20 轮进上下文，限制 40 条消息，7 天过期；会话按用户隔离，仅归属者可访问；Redis 不可用时降级不阻断主流程。

限流策略：POST /api/agent/chat 30/min、POST /api/bi/analyze 20/min、知识上传 20/min，基于 Redis 计数，超限返回 429。

向量库：pgvector 1024 维 HNSW，COSINE_DISTANCE 距离，兼容 text-embedding-3-small 与本地 Ollama qwen3-embedding（1024 维）；离线演示使用 Mock 伪向量。

RAG 召回：vector top8 相似度阈值 0.2，重排模式 auto/bm25/cross-encoder，auto 优先 cross-encoder（Python 边车），失败回退 BM25 复合打分取 top4；向量召回失败时关键词 ILIKE 兜底，参数化防注入。
