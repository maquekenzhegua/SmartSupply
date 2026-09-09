"""Agent 图核心回归 —— 直接测 app.graph 的真实代码。

架构约束：规划只走 function-calling 或 JSON 文本解析，图内不再有
关键词规则伪装；离线确定性来自 Mock provider（llm._mock_provider），其规划结果
在 trace 中以 provider="mock" 如实标注。
"""
import asyncio

import pytest

from app import config
from app import graph as G
from app.graph import (
    MAX_ITERS,
    TOOL_REGISTRY,
    _extract_json_array,
    _validate_calls,
    planner,
    reasoner,
    tool_specs,
)
from app.llm import LLMUnavailable
from app.tools import TOOL_DEFS


def _state(question: str) -> dict:
    return {
        "messages": [{"role": "user", "content": question}],
        "agent_type": "general",
        "tool_results": [],
        "trace": [],
        "iters": 0,
        "pending_tool_calls": [],
        "final": "",
        "degraded": False,
        "degrade_reason": "",
        "usage": {},
    }


def _plan(question: str) -> list:
    result = asyncio.run(planner(_state(question)))
    return result.get("pending_tool_calls") or []


# ---------- provider 与基础解析 ----------

def test_extract_json_array_real_parser():
    assert _extract_json_array('前言 [{"tool":"list_low_stock","args":{}}] 后缀') == [
        {"tool": "list_low_stock", "args": {}}
    ]
    assert _extract_json_array("[]") == []
    assert _extract_json_array("这不是 JSON") is None
    assert _extract_json_array('[{"tool": broken}') is None
    assert _extract_json_array('{"tool":"x"}') is None  # 非数组返回 None 而非异常


def test_tool_specs_cover_registry_and_are_function_calling_shape():
    specs = tool_specs()
    names = {s["function"]["name"] for s in specs}
    assert names == set(TOOL_REGISTRY) == {d["name"] for d in TOOL_DEFS}
    for s in specs:
        fn = s["function"]
        assert fn["description"] and s["type"] == "function"
        assert fn["parameters"]["type"] == "object"


# ---------- 参数校验（不伪造默认值） ----------

def test_validate_calls_filters_bad_and_coerces_types():
    calls, dropped = _validate_calls([
        {"tool": "no_such_tool", "args": {}},
        {"tool": "get_contract_risk", "args": {"contract_id": "3"}},      # 字符串数字可转换
        {"tool": "get_contract_risk", "args": {"contract_id": "abc"}},    # 转换失败：丢弃而非默认成 1
        {"tool": "search_contracts", "args": {}},                          # 缺必填：丢弃
        {"tool": "list_low_stock", "args": None},                          # 无参工具容忍
    ])
    assert [c["tool"] for c in calls] == ["get_contract_risk", "list_low_stock"]
    assert calls[0]["args"]["contract_id"] == 3
    reasons = " ".join(d["reason"] for d in dropped)
    assert "unknown-tool" in reasons and "bad-integer" in reasons and "missing-required" in reasons


# ---------- planner：function-calling 路径 ----------

def test_planner_uses_native_tool_calls(monkeypatch):
    seen = {}

    async def fake_chat(messages, tools=None, model=None):
        seen["tools"] = tools
        return {"text": None,
                "tool_calls": [{"name": "search_contracts", "arguments": {"keyword": "违约金"}}],
                "provider": "openai"}

    monkeypatch.setattr(G, "chat", fake_chat)
    out = asyncio.run(planner(_state("合同违约金条款有什么风险")))
    assert seen["tools"] and all(t["function"]["name"] in TOOL_REGISTRY for t in seen["tools"])
    assert out["pending_tool_calls"] == [{"tool": "search_contracts", "args": {"keyword": "违约金"}}]
    assert out["trace"][-1]["provider"] == "openai"
    assert not out.get("degraded")


def test_planner_parses_json_text_fallback(monkeypatch):
    async def fake_chat(messages, tools=None, model=None):
        return {"text": '[{"tool":"list_low_stock","args":{}}]', "tool_calls": None, "provider": "openai"}

    monkeypatch.setattr(G, "chat", fake_chat)
    calls = asyncio.run(planner(_state("哪些SKU低于安全库存")))["pending_tool_calls"]
    assert calls == [{"tool": "list_low_stock", "args": {}}]


def test_planner_llm_unavailable_marks_degraded_not_faked(monkeypatch):
    """LLM 不可用时如实 degraded：不再走图内关键词规则"伪装规划成功"。"""
    async def boom(messages, tools=None, model=None):
        raise LLMUnavailable("muse /responses 调用失败: timeout", provider="muse")

    monkeypatch.setattr(G, "chat", boom)
    out = asyncio.run(planner(_state("查询低库存有哪些 SKU")))
    assert out["pending_tool_calls"] == []
    assert out["degraded"] is True
    assert "LLM 不可用" in out["degrade_reason"]


def test_planner_mock_provider_plans_by_task(monkeypatch):
    """离线 Mock：规划发生在 provider（如实标 provider=mock），图内无规则。"""
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    calls = _plan("查询低库存有哪些 SKU")
    assert calls and calls[0]["tool"] == "list_low_stock"
    calls = _plan("帮我看下合同违约金条款风险")
    assert calls and calls[0]["tool"] == "search_contracts"
    calls = _plan("搜索 T恤 商品")
    assert calls and calls[0]["tool"] == "search_catalog"
    calls = _plan("你好")
    assert calls == []


# ---------- reasoner：失败如实、部分失败声明 ----------

def test_reasoner_all_tools_failed_honest_answer():
    st = _state("查低库存")
    st["tool_results"] = [
        {"tool": "list_low_stock", "args": {}, "ok": False,
         "result": {"tool": "list_low_stock", "ok": False, "error": "Java API 403: forbidden", "auth_failed": True}},
        {"tool": "list_suppliers", "args": {}, "ok": False,
         "result": {"tool": "list_suppliers", "ok": False, "error": "connection refused", "auth_failed": False}},
    ]
    out = asyncio.run(reasoner(st))
    assert out["degraded"] is True and out["degrade_reason"] == "all_tool_calls_failed"
    assert "403" in out["final"] and "connection refused" in out["final"]
    assert "无法完成" in out["final"]


def test_reasoner_reasoner_llm_down_still_returns_evidence(monkeypatch):
    async def boom(messages, tools=None, model=None):
        raise LLMUnavailable("muse 推理截断", provider="muse")

    monkeypatch.setattr(G, "chat", boom)
    st = _state("查低库存")
    st["tool_results"] = [{"tool": "list_low_stock", "args": {}, "ok": True,
                           "result": {"tool": "list_low_stock", "ok": True, "data": [{"sku_code": "SKU-A"}]}}]
    out = asyncio.run(reasoner(st))
    assert out["degraded"] is True
    assert "SKU-A" in out["final"]           # 真实取证数据保留
    assert "语言模型暂不可用" in out["final"]  # 不冒充模型组织


def test_max_iters_guard():
    st = _state("查低库存")
    st["iters"] = MAX_ITERS
    out = asyncio.run(planner(st))
    assert out["pending_tool_calls"] == []


def test_validate_calls_rejects_nan_inf_and_bool():
    """数值卫生：NaN/inf 不得作为参数流向 Java（Jackson 默认拒绝非法数值），bool 不得
    静默转成 1（int(True) 的 Python 坑）。"""
    bads = [
        {"tool": "create_purchase_order",
         "args": {"supplier_id": 1, "sku_code": "SKU-A", "quantity": 1, "unit_price": float("nan")}},
        {"tool": "create_purchase_order",
         "args": {"supplier_id": 1, "sku_code": "SKU-A", "quantity": 1, "unit_price": float("inf")}},
        {"tool": "create_purchase_order",
         "args": {"supplier_id": 1, "sku_code": "SKU-A", "quantity": True, "unit_price": 1.0}},
    ]
    calls, dropped = G._validate_calls(bads)
    assert calls == []
    reasons = " ".join(d["reason"] for d in dropped)
    assert reasons.count("bad-number") == 2 and "bad-integer" in reasons
