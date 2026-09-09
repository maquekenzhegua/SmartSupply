r"""
RAG 侧回归（离线可跑，无需真实 LLM/PG）：
- rerank：词面打分排序正确性 + cross-encoder 回退事实标记（此前 Python 侧零覆盖，
  旧"切分/chromadb"用例测试的是用例自己内联的实现与第三方库，对产品代码零覆盖，已移除）
- 召回端点：诚实转发 Java（成功透传 / 不可达 502）
"""
import pytest

from fastapi.testclient import TestClient

from app.main import app

from app import rerank as R


DOCS = [
    {"id": 1, "title": "风控规范", "content": "禁止无限连带责任条款，违约金不得超过合同额30%"},
    {"id": 2, "title": "库存制度", "content": "SKU 低于安全库存时应触发补货流程"},
    {"id": 3, "title": "交付条款", "content": "合同约定尽快交付不算明确的交付时间"},
]


def test_bm25_mode_ranks_relevant_doc_first():
    out = R.rerank("无限连带责任 违约金", DOCS, top_k=3, mode="bm25")
    assert out["mode"] == "bm25" and out["fallback"] is False
    assert out["reranked"][0]["id"] == 1, "与查询强相关的文档必须排第一"
    assert len(out["scores"]) == 3 and out["scores"][0] >= out["scores"][-1]


def test_empty_docs_short_circuits():
    out = R.rerank("任意", [], top_k=2, mode="bm25")
    assert out == {"reranked": [], "mode": "none", "fallback": False}


def test_cross_encoder_unavailable_marks_fallback(monkeypatch):
    """cross-encoder 不可用时回退词面打分，且回退事实必须体现在 fallback 字段。"""
    monkeypatch.setattr(R, "_try_load_cross", lambda: None)
    monkeypatch.setattr(R, "_cross_failed", True)
    out = R.rerank("无限连带责任", DOCS, top_k=2, mode="auto")
    assert out["mode"] == "bm25" and out["fallback"] is True
    assert out["reranked"][0]["id"] == 1


def test_cross_encoder_predict_failure_marks_fallback(monkeypatch):
    """加载成功但 predict 失败同样属于回退，绝不能让调用方误以为结果是 cross-encoder 打分。"""
    class Boom:
        def predict(self, pairs):
            raise RuntimeError("cuda oom")
    monkeypatch.setattr(R, "_try_load_cross", lambda: Boom())
    monkeypatch.setattr(R, "_cross_failed", False)
    out = R.rerank("库存 补货", DOCS, top_k=2, mode="auto")
    assert out["mode"] == "bm25" and out["fallback"] is True
    assert out["reranked"][0]["id"] == 2


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
