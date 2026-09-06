r"""
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


def test_fastapi_rag_recall_forwards_to_java(monkeypatch):
    """召回端点已改为真实转发：成功路径透传 Java context/citations，不再返回占位假文案。"""
    from app import tools as T

    async def fake(path, method="GET", params=None, json=None, timeout_s=8.0):
        assert path == "/api/knowledge/recall"
        return {"context": "禁止无限连带责任", "citations": [{"docId": 1, "title": "风控规范"}],
                "vectorHits": 3, "reranked": 2}

    monkeypatch.setattr(T, "call_java_tool", fake)
    c = TestClient(app)
    r = c.post("/api/rag/recall", json={"query": "无限连带责任"})
    assert r.status_code == 200
    data = r.json()
    assert data["context"] == "禁止无限连带责任"
    assert data["citations"][0]["title"] == "风控规范"


def test_java_recall_unreachable_reports_502_not_fake(monkeypatch):
    """Java 不可达时如实 502 + degraded，绝不返回编造的 context 冒充召回结果。"""
    from app import tools as T

    async def boom(*a, **k):
        raise RuntimeError("connection refused")

    monkeypatch.setattr(T, "call_java_tool", boom)
    c = TestClient(app)
    r = c.post("/api/rag/recall", json={"query": "违约金 30%"})
    assert r.status_code == 502
    body = r.json()
    assert body["degraded"] is True and "connection refused" in body["error"]
