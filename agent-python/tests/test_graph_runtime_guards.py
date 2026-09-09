"""Agent 图运行时守卫回归（审计实锤的四个失控缺陷 + 流式事件重发 + 断连不取消）：

  1. MAX_ITERS 失效：reflector 不推进 iters → 模型只要持续规划新参数调用就无限循环；
  2. 预算闸门死代码：只在入口 planner 判一次（此时 usage 恒为 0）→ 永不触发，无界成本；
  3. 批量 ≥2 写调用：挂起检测只看 snap.next/values.__interrupt__，第二次挂起位于
     tasks[*].interrupts → guard 误判"无挂起"拒恢复；且节点重放会把第一个写重复执行；
  4. 恢复后 SSE 把 checkpoint 里的历史 tool 结果错标重发（emitted_tools 恒 0）；
  5. 客户端断连（生成器关闭）后 _drive 任务继续跑完整图，LLM 照常烧钱。

离线确定性：全程 monkeypatch LLM 与 Java 回环；写路径经 TestClient 走真实 interrupt/resume 契约。
"""
import asyncio
import json

from fastapi.testclient import TestClient

from app import config
from app import graph as G
from app import tools as T
from app.main import app


def _sse_events(client, **payload):
    payload.setdefault("messages", [])  # resume 场景 messages 被忽略但仍为必填字段
    with client.stream("POST", "/api/reason/stream", json={
            "agentType": "general", "sessionId": "guards", **payload}) as resp:
        assert resp.status_code == 200
        event, data = "message", ""
        out = []
        for line in resp.iter_lines():
            line = (line or "").rstrip("\r")
            if line.startswith("event:"):
                event = line[6:].strip()
                continue
            if line.startswith("data:"):
                data += line[5:].lstrip(" ")
                continue
            if not line and data:
                out.append((event, json.loads(data)))
                event, data = "message", ""
    return out


# ---------- 1. MAX_ITERS 必须封顶 reflector 循环 ----------

def test_max_iters_bounds_reflector_loop(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    monkeypatch.setattr(config, "MAX_ITERATIONS", 3)
    round_n = {"n": 0}

    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools and "已有工具结果" not in last:
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": "first"}}]),
                    "tool_calls": None, "provider": "stub"}
        if tools:
            # 反思器永远要求新证据（每轮参数不同，绕开 (tool,args) 去重集）
            round_n["n"] += 1
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": f"kw-{round_n['n']}"}}]),
                    "tool_calls": None, "provider": "stub"}
        return {"text": "基于已取证数据作答", "tool_calls": None, "provider": "stub"}

    async def fake_java(path, method="GET", params=None, json=None, timeout_s=8.0):
        return [{"sku_code": "S1"}]

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(T, "call_java_tool", fake_java)
    # 直接驱动图（非 HTTP）：无界循环以 wait_for 超时变红，而非挂死测试进程
    d = asyncio.run(asyncio.wait_for(
        G.run_reasoning_with_trace([{"role": "user", "content": "查商品"}], "general", "iters"),
        timeout=15))
    assert d["degraded"] is False, d
    reflects = [e for e in d["trace"] if e.get("node") == "reflector"]
    assert len(reflects) <= 3, f"reflector 循环未被 MAX_ITERS 封顶: {len(reflects)} 轮"
    assert d["iters"] <= 4
    assert d["reply"], "封顶后仍必须收敛出最终回答"


# ---------- 2. 预算闸门必须在真实路径上生效 ----------

def test_budget_exhaustion_stops_loop_via_real_path(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    monkeypatch.setattr(config, "RUN_TOKEN_BUDGET", 50)
    round_n = {"n": 0}

    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools and "已有工具结果" not in last:
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": "first"}}]),
                    "tool_calls": None, "provider": "stub",
                    "usage": {"prompt_tokens": 10, "completion_tokens": 2}}
        if tools:
            round_n["n"] += 1
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": f"b-{round_n['n']}"}}]),
                    "tool_calls": None, "provider": "stub",
                    "usage": {"prompt_tokens": 40, "completion_tokens": 5}}
        return {"text": "预算收口后的回答", "tool_calls": None, "provider": "stub"}

    async def fake_java(path, method="GET", params=None, json=None, timeout_s=8.0):
        return [{"sku_code": "S1"}]

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(T, "call_java_tool", fake_java)
    d = asyncio.run(asyncio.wait_for(
        G.run_reasoning_with_trace([{"role": "user", "content": "查商品"}], "general", "budget"),
        timeout=15))
    assert d["budget"]["exhausted"] is True, f"真实路径下预算闸门未触发: {d['budget']}"
    reflects = [e for e in d["trace"] if e.get("node") == "reflector"]
    assert len(reflects) < 6, "预算收口应早于 MAX_ITERS 上限"
    assert d["reply"], "收口后仍必须给出基于现有证据的回答"
    assert not d["degraded"], "预算收口是策略行为，不算 degraded"


# ---------- 3+4. 批量双写：每轮一个、跨轮串行、各执行一次 ----------

S1 = {"supplier_id": 1, "sku_code": "SKU-A", "quantity": 1, "unit_price": 1.0}
S2 = {"supplier_id": 2, "sku_code": "SKU-B", "quantity": 2, "unit_price": 2.0}
# Java 回环 payload 为 camelCase（工具层发送格式），与 interrupt 载荷的 snake_case 不同
S1_JAVA = {"supplierId": 1, "skuCode": "SKU-A", "quantity": 1, "unitPrice": 1.0}
S2_JAVA = {"supplierId": 2, "skuCode": "SKU-B", "quantity": 2, "unitPrice": 2.0}


def _patch_two_write_flow(monkeypatch, executed):
    """planner 一次规划两个写调用；反思器在 S1 执行后重新规划被限流的 S2，随后收敛。"""
    planner_done = {"done": False}

    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools and "已有工具结果" not in last:
            if not planner_done["done"]:
                planner_done["done"] = True
                return {"text": None, "tool_calls": [
                    {"name": "create_purchase_order", "arguments": dict(S1)},
                    {"name": "create_purchase_order", "arguments": dict(S2)}], "provider": "stub"}
            return {"text": "[]", "tool_calls": None, "provider": "stub"}
        if tools:
            if "SKU-B" not in last:  # S2 尚未执行 → 重新规划
                return {"text": json.dumps([{"tool": "create_purchase_order", "args": dict(S2)}]),
                        "tool_calls": None, "provider": "stub"}
            return {"text": "[]", "tool_calls": None, "provider": "stub"}
        return {"text": "两个采购单均已创建", "tool_calls": None, "provider": "stub"}

    async def fake_java(path, method="GET", params=None, json=None, timeout_s=8.0):
        if path == "/api/agent/purchase-orders":
            executed.append(json)
            return {"success": True, "orderNo": f"PO-{len(executed)}"}
        raise AssertionError(f"unexpected java call: {path}")

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(T, "call_java_tool", fake_java)


def test_two_write_batch_each_executes_exactly_once(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    executed = []
    _patch_two_write_flow(monkeypatch, executed)
    client = TestClient(app)

    d1 = client.post("/api/reason", json={
        "messages": [{"role": "user", "content": "给两个供应商各下个单"}], "agentType": "general",
        "sessionId": "twow"}).json()
    assert d1["interrupted"] is True and d1["confirm"], d1
    assert d1["confirm"][0]["args"] == S1, "批量双写每轮只放行一个（limit=1）"
    assert "over-write-batch-limit" in json.dumps(d1["trace"]), "被限流的第二个写必须留痕"
    assert executed == [], "未批准不执行"

    # 批准 S1：写恰好执行一次；随后 S2 重新规划并再次挂起（此前 guard 误判为"无挂起"直接拒绝）
    d2 = client.post("/api/reason", json={
        "messages": [], "agentType": "general", "sessionId": "twow",
        "threadId": d1["threadId"], "resume": {"approved": True}}).json()
    assert executed == [S1_JAVA], f"S1 必须恰好执行一次（节点重放不得重复执行）: {executed}"
    assert d2["interrupted"] is True and d2["confirm"][0]["args"] == S2, \
        f"第二次挂起必须被识别为待恢复: {d2.get('degrade_reason', '')}"

    # 批准 S2：各恰好一次，绝不允许 [S1, S1, S2]
    d3 = client.post("/api/reason", json={
        "messages": [], "agentType": "general", "sessionId": "twow",
        "threadId": d1["threadId"], "resume": {"approved": True}}).json()
    assert executed == [S1_JAVA, S2_JAVA], f"两个写各执行一次: {executed}"
    assert d3["interrupted"] is False and d3["reply"], d3


# ---------- 5. 恢复后 SSE 不得把历史 tool 结果错标重发 ----------

def test_stream_resume_does_not_reemit_history(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    executed = []
    phase = {"reflector_reads": False}

    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools and "已有工具结果" not in last:
            return {"text": None, "tool_calls": [{"name": "create_purchase_order", "arguments": dict(S1)}],
                    "provider": "stub"}
        if tools:
            if not phase["reflector_reads"]:
                phase["reflector_reads"] = True
                return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": "after-write"}}]),
                        "tool_calls": None, "provider": "stub"}
            return {"text": "[]", "tool_calls": None, "provider": "stub"}
        return {"text": "已创建", "tool_calls": None, "provider": "stub"}

    async def fake_java(path, method="GET", params=None, json=None, timeout_s=8.0):
        executed.append(path)
        return [{"sku_code": "S1"}]

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(T, "call_java_tool", fake_java)
    client = TestClient(app)

    events1 = _sse_events(client, messages=[{"role": "user", "content": "下单后顺带查下商品"}])
    confirm = dict(events1)["confirm_required"]
    assert confirm["tool"] == "create_purchase_order"

    events2 = _sse_events(client, threadId=confirm["thread_id"], resume={"approved": True})
    tool_events = [d for n, d in events2 if n == "tool"]
    write_events = [d for n, d in events2 if n == "write_tool"]
    # 写操作只能以 write_tool 出现一次；绝不许恢复后以 tool 事件把已执行的写重发一遍
    assert [e["tool"] for e in write_events] == ["create_purchase_order"]
    assert not [e for e in tool_events if e["tool"] == "create_purchase_order"], \
        f"历史写结果被错标为 tool 事件重发: {[e['tool'] for e in tool_events]}"
    assert [e["tool"] for e in tool_events] == ["search_catalog"]
    done = dict(events2)["done"]
    assert done["interrupted"] is False


# ---------- 6. 客户端断连必须取消图执行 ----------

def test_generator_close_cancels_driver(monkeypatch):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    reflector_starts = {"n": 0}

    async def fake_chat(messages, tools=None, model=None):
        last = messages[-1].get("content") or ""
        if tools and "已有工具结果" not in last:
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": "x"}}]),
                    "tool_calls": None, "provider": "stub"}
        if tools:
            reflector_starts["n"] += 1
            await asyncio.sleep(0.15)
            return {"text": json.dumps([{"tool": "search_catalog", "args": {"keyword": f"k{reflector_starts['n']}"}}]),
                    "tool_calls": None, "provider": "stub"}
        return {"text": "done", "tool_calls": None, "provider": "stub"}

    async def fake_java(path, method="GET", params=None, json=None, timeout_s=8.0):
        return [{"sku_code": "S1"}]

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(T, "call_java_tool", fake_java)

    async def scenario():
        gen = G.stream_reasoning_events(
            [{"role": "user", "content": "查商品"}], "general", "disc")
        first = await gen.__anext__()          # plan 事件到达即认为消费者已建立
        assert first.get("event") == "plan"
        await gen.aclose()                     # 模拟客户端断连
        await asyncio.sleep(0.5)
        n1 = reflector_starts["n"]
        await asyncio.sleep(0.5)
        return n1, reflector_starts["n"]

    n1, n2 = asyncio.run(scenario())
    assert n2 == n1, f"断连后图仍在继续执行（0.5s 内 reflector 又启动了 {n2 - n1} 次 LLM 调用）"
