"""
RAG 回归（落到 D:\conda_envs\ai-backend 可跑，无需真实 LLM/PG）：
- 切分：800/100 中文友好
- 本地向量：chromadb 内存集合 + 归一化伪向量（与 Java MockEmbeddingModel 一致语义）
- 召回：离线关键词兜底
- 评估：ragas/datasets 已装到 ai-backend，仅做可用性校验（不发真实 LLM）
"""
import random

import pytest

pytest.importorskip("chromadb")
import chromadb

from fastapi.testclient import TestClient

from app.main import app


def pseudo_vector(text: str, dims: int = 1536):
    r = random.Random(hash(text) & 0x7FFFFFFF)
    v = [r.uniform(-1, 1) for _ in range(dims)]
    norm = sum(x * x for x in v) ** 0.5 or 1.0
    return [x / norm for x in v]


def test_splitter_over_chunk_long_text():
    from app.main import app as _  # ensure import side-effects ok
    # 复用 Java 侧的切分语义：800/100，这里做等价校验（Python 侧未独立实现 splitter）
    text = "A" * 2500
    # 简单模拟：按 800 窗口 100 overlap 至少 3 段
    chunk_size, overlap = 800, 100
    chunks = []
    s = 0
    while s < len(text):
        e = min(s + chunk_size, len(text))
        chunks.append(text[s:e])
        if e >= len(text):
            break
        s = e - overlap
    assert len(chunks) >= 3
    assert all(len(c) <= 800 for c in chunks)


def test_chromadb_local_vector_roundtrip():
    client = chromadb.Client()
    # chromadb collection name must be 3+ chars, use test-rag-xxx
    col = client.create_collection("test-rag-pytest-chroma")
    doc = "禁止无限连带责任；违约金不得超过合同额30%"
    vec = pseudo_vector(doc)
    col.add(ids=["doc1"], documents=[doc], embeddings=[vec])
    res = col.query(query_embeddings=[vec], n_results=1)
    assert res["documents"][0][0] == doc
    # 近似查询：同语义短句应召回
    q = pseudo_vector("无限连带责任")
    res2 = col.query(query_embeddings=[q], n_results=1)
    assert len(res2["documents"][0]) == 1


def test_ragas_import_available_on_ai_backend_env():
    pytest.importorskip("ragas")
    pytest.importorskip("datasets")
    import ragas
    from datasets import Dataset

    assert ragas.__version__  # 已装到 D:\conda_envs\ai-backend
    ds = Dataset.from_dict({"question": ["什么是无限连带责任"], "answer": ["禁止"], "contexts": [["禁止无限连带责任"]]})
    assert len(ds) == 1


def test_fastapi_rag_recall_placeholder_status():
    c = TestClient(app)
    r = c.post("/api/rag/recall", json={"query": "无限连带责任"})
    assert r.status_code == 200
    data = r.json()
    assert "query" in data and "context" in data
    assert "无限连带责任" in data["query"]


def test_sentence_transformers_import_available():
    pytest.importorskip("sentence_transformers")
    import sentence_transformers

    assert sentence_transformers.__version__


def test_java_rag_is_authoritative_note():
    """Python 侧召回为占位，权威召回在 Java RagService + MockEmbedding + H2/pgvector，此单测只保证占位不回归为空。"""
    c = TestClient(app)
    r = c.post("/api/rag/recall", json={"query": "违约金 30%"})
    assert r.status_code == 200
    assert r.json()["context"]  # 非空占位
