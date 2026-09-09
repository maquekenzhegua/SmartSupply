"""HITL 写闸门端到端回归：/api/reason 与 /api/reason/stream 的 interrupt/resume 契约。

契约（与 Java PythonSidecarService / 前端 AgentChat 三方对齐）：
  - 挂起：事件流出现 confirm_required{thread_id, tool, args, question}，done(interrupted=true)；
    非流式响应 interrupted=true + confirm[] + threadId。此时写工具绝不执行。
  - 恢复：携 threadId + resume={"approved": bool} 调用同端点 → 批准执行 / 拒绝如实取消。
  - 恢复不存在的挂起（边车重启等）：显式 degraded，绝不伪造成功。
离线确定性：monkeypatch 的 LLM 规划与 Java 回环。
"""
import json

from fastapi.testclient import TestClient

from app import config
from app import graph as G
from app import tools as T
from app.main import app

WRITE_ARGS = {"supplier_id": 1, "sku_code": "SKU-T001-WH-M", "quantity": 100, "unit_price": 9.9}


def _patch_llm_plan_write(monkeypatch):
    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools:
            if "已有工具结果" in last:
                return {"text": "[]", "tool_calls": None, "provider": "stub"}
            return {"text": None,
                    "tool_calls": [{"name": "create_purchase_order", "arguments": dict(WRITE_ARGS)}],
                    "provider": "stub"}
        return {"text": "[Mock] 采购单已创建", "tool_calls": None, "provider": "stub"}

    async def fake_stream(messages, usage_out=None, model=None):
        for ch in ["已", "创", "建"]:
            yield ch

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(G, "chat_stream", fake_stream)


def _patch_java_write(monkeypatch, executed):
    async def fake_call(path, method="GET", params=None, json=None, timeout_s=8.0):
        if path == "/api/agent/purchase-orders":
            executed.append(json)
            return {"success": True, "orderNo": "PO-TEST-0001"}
        raise AssertionError(f"unexpected java call: {path}")

    monkeypatch.setattr(T, "call_java_tool", fake_call)


def test_stream_interrupt_then_reject(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_llm_plan_write(monkeypatch)
    executed = []
    _patch_java_write(monkeypatch, executed)
    client = TestClient(app)

    events = []
    with client.stream("POST", "/api/reason/stream", json={
            "messages": [{"role": "user", "content": "帮我下单"}], "agentType": "general", "sessionId": "hitl"}) as resp:
        assert resp.status_code == 200
        event, data = "message", ""
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
    names = [n for n, _ in events]
    assert "confirm_required" in names, names
    confirm = dict(events)["confirm_required"]
    assert confirm["tool"] == "create_purchase_order" and confirm["args"]["quantity"] == 100
    assert confirm["thread_id"], "confirm_required 必须携带 thread_id 供恢复"
    done = dict((n, d) for n, d in events if n == "done")["done"]
    assert done["interrupted"] is True and done["thread_id"] == confirm["thread_id"]
    assert executed == [], "未批准时写工具绝不执行"

    # 拒绝恢复：写工具仍不执行，回答如实说明已取消
    r = client.post("/api/reason/stream", json={
        "messages": [], "agentType": "general", "sessionId": "hitl",
        "threadId": confirm["thread_id"], "resume": {"approved": False}})
    assert r.status_code == 200
    body = r.text
    assert "已取消" in body and executed == []


def test_stream_interrupt_then_approve_executes_write(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_llm_plan_write(monkeypatch)
    executed = []
    _patch_java_write(monkeypatch, executed)
    client = TestClient(app)

    with client.stream("POST", "/api/reason/stream", json={
            "messages": [{"role": "user", "content": "帮我下单"}], "agentType": "general", "sessionId": "hitl2"}) as resp:
        confirm = None
        event, data = "message", ""
        for line in resp.iter_lines():
            line = (line or "").rstrip("\r")
            if line.startswith("event:"):
                event = line[6:].strip()
                continue
            if line.startswith("data:"):
                data += line[5:].lstrip(" ")
                continue
            if not line and data:
                if event == "confirm_required":
                    confirm = json.loads(data)
                event, data = "message", ""
    assert confirm, "首轮必须挂起"

    r = client.post("/api/reason/stream", json={
        "messages": [], "agentType": "general", "sessionId": "hitl2",
        "threadId": confirm["thread_id"], "resume": {"approved": True}})
    assert r.status_code == 200
    assert executed and executed[0]["quantity"] == 100, "批准后写工具真实执行"


def test_blocking_interrupt_and_resume_contract(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_llm_plan_write(monkeypatch)
    executed = []
    _patch_java_write(monkeypatch, executed)
    client = TestClient(app)
    body = {"messages": [{"role": "user", "content": "帮我下单"}], "agentType": "general", "sessionId": "hitl3"}
    r1 = client.post("/api/reason", json=body)
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1["interrupted"] is True and d1["confirm"] and d1["threadId"], d1
    assert d1["confirm"][0]["tool"] == "create_purchase_order"
    assert executed == []
    # 批准恢复
    r2 = client.post("/api/reason", json={"messages": [], "agentType": "general", "sessionId": "hitl3",
                                          "threadId": d1["threadId"], "resume": {"approved": True}})
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2["interrupted"] is False and executed, d2
    assert d2["threadId"] == d1["threadId"]


def test_resume_without_pending_interrupt_is_honest(monkeypatch):
    """恢复不存在的挂起（边车重启丢 checkpoint）：显式 degraded，不伪造执行成功。"""
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    client = TestClient(app)
    r = client.post("/api/reason", json={"messages": [], "agentType": "general", "sessionId": "hitl4",
                                         "threadId": "no-such-thread:00000000", "resume": {"approved": True}})
    assert r.status_code == 200
    d = r.json()
    assert d["degraded"] is True and "no_pending_interrupt" in d["degrade_reason"], d
