"""深度模式 SSE 端点回归 —— /api/reason/stream 事件契约：

  plan -> tool* -> reflect -> reply_delta* -> done。
  reply_delta 为 provider 原生 token 增量（真流式）；全部取证失败等未走流式的
  收口路径由 driver 整段补发一条 reply_delta，保证消费方总能拿到回答正文。
  离线确定性：LLM_MODE=mock + monkeypatch 的 Java 回环。
"""
import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from app import config
from app import graph as G
from app import tools as T
from app.main import app

JAVA_OK = {
    "/api/inventory/low-stock": [{"sku_code": "SKU-T001-WH-M", "quantity": 120, "safety_stock": 200}],
}


def _patch_java(monkeypatch, ok=True):
    async def fake(path, method="GET", params=None, json=None, timeout_s=8.0):
        if not ok:
            raise RuntimeError("connection refused")
        return JAVA_OK["/api/inventory/low-stock"]
    monkeypatch.setattr(T, "call_java_tool", fake)


def _read_sse(client, question):
    """消费 SSE 流，返回 (事件名, 数据) 有序列表。"""
    events = []
    with client.stream("POST", "/api/reason/stream", json={
            "messages": [{"role": "user", "content": question}], "agentType": "general", "sessionId": "t"}) as resp:
        assert resp.status_code == 200
        event = "message"
        data = ""
        for line in resp.iter_lines():
            line = (line or "").rstrip("\r")
            if line.startswith("event:"):
                event = line[6:].strip()
                continue
            if line.startswith("data:"):
                data += line[5:].lstrip(" ")
                continue
            if not line and data:
                events.append((event, json.loads(data)))
                event, data = "message", ""
    return events


def _names(events):
    return [e for e, _ in events]


def _reply_text(events):
    return "".join(d.get("text", "") for n, d in events if n == "reply_delta")


def test_stream_event_sequence_and_contract(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_java(monkeypatch, ok=True)
    client = TestClient(app)
    events = _read_sse(client, "哪些SKU低于安全库存？")
    names = _names(events)
    # 顺序契约：plan -> tool -> reflect -> reply_delta* -> done（真流式，无整段 reply）
    assert names[:3] == ["plan", "tool", "reflect"], names
    assert names[-1] == "done", names
    assert "reply" not in set(names), names
    assert names.count("reply_delta") >= 1, names  # mock 按 24 字符切片 → 多个增量
    by_first = {n: d for n, d in events if n in ("plan", "tool", "reflect", "done")}
    plan = by_first["plan"]
    assert plan["calls"] and plan["calls"][0]["tool"] == "list_low_stock"
    assert plan["provider"] == "mock"  # Mock 规划如实标注
    tool = by_first["tool"]
    assert tool["tool"] == "list_low_stock" and tool["ok"] is True
    assert tool["args"] == {}  # 真实下发的参数
    assert _reply_text(events)
    done = by_first["done"]
    assert done["degraded"] is False
    assert done["tools"] == ["list_low_stock"]


def test_stream_honest_when_java_down(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")

    async def boom(*a, **k):
        raise RuntimeError("connection refused")
    monkeypatch.setattr(T, "call_java_tool", boom)
    client = TestClient(app)
    events = _read_sse(client, "哪些SKU低于安全库存？")
    by_name = dict(events)
    assert by_name["done"]["degraded"] is True
    assert by_name["done"]["degrade_reason"] == "all_tool_calls_failed"
    # 全部取证失败：正文由 driver 整段补发（一条 reply_delta），如实报告而非沉默
    assert "无法完成" in _reply_text(events)
    assert by_name["tool"]["ok"] is False  # 取证失败也逐事件如实下发


def test_stream_state_consistent_with_blocking(monkeypatch):
    """同一输入下，事件流汇总的 reply/degraded 与阻塞端点一致（两套出口不漂移）。"""
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_java(monkeypatch, ok=True)
    client = TestClient(app)
    stream_events = _read_sse(client, "哪些SKU低于安全库存？")
    assert _reply_text(stream_events) == asyncio.run(
        G.run_reasoning_with_trace([{"role": "user", "content": "哪些SKU低于安全库存？"}]))["reply"]
    assert dict(stream_events)["done"]["degraded"] is False


def test_stream_requires_api_key_when_configured(monkeypatch):
    """配置 SIDECAR_API_KEY 后，推理端点要求 X-Api-Key 匹配（内网裸奔封堵）。"""
    monkeypatch.setattr(config, "SIDECAR_API_KEY", "secret-key-123")
    client = TestClient(app)
    body = {"messages": [{"role": "user", "content": "你好"}], "agentType": "general", "sessionId": "t"}
    r0 = client.post("/api/reason", json=body)
    assert r0.status_code == 401
    r1 = client.post("/api/reason", json=body, headers={"X-Api-Key": "wrong"})
    assert r1.status_code == 401
    # 健康探针保持公开
    assert client.get("/health").status_code == 200
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_java(monkeypatch, ok=True)
    r2 = client.post("/api/reason", json=body, headers={"X-Api-Key": "secret-key-123"})
    assert r2.status_code == 200
    # 未配置密钥（默认）时保持零配置可用
    monkeypatch.setattr(config, "SIDECAR_API_KEY", "")
    r3 = client.post("/api/reason", json=body)
    assert r3.status_code == 200


def test_rag_endpoints_require_api_key_when_configured(monkeypatch):
    """鉴权面完整性回归：/api/rag/recall、/api/rag/rerank 与 /api/reason* 同受 X-Api-Key 保护
    （此前这两个端点漏在鉴权面外，配了 key 也拦不住 recall 的 Java 回环与 rerank 的 CPU 推理）。"""
    monkeypatch.setattr(config, "SIDECAR_API_KEY", "secret-key-123")
    client = TestClient(app)
    assert client.post("/api/rag/recall", json={"query": "风控"}).status_code == 401
    assert client.post("/api/rag/rerank", json={"query": "q", "docs": []}).status_code == 401
    assert client.post("/api/rag/recall", json={"query": "风控"},
                       headers={"X-Api-Key": "secret-key-123"}).status_code in (200, 502)  # 已过鉴权（502=Java 不可达）
    assert client.post("/api/rag/rerank", json={"query": "q", "docs": [], "mode": "bm25"},
                       headers={"X-Api-Key": "secret-key-123"}).status_code == 200
    # 未配置密钥（默认）时零配置可用
    monkeypatch.setattr(config, "SIDECAR_API_KEY", "")
    assert client.post("/api/rag/rerank", json={"query": "q", "docs": []}).status_code == 200
