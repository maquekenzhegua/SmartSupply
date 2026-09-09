"""Agent 平台化行为测试（差距矩阵补强的回归面）：
- 差异化工具集：bi 只读、contract 聚焦知识取证，写工具在规划层就不可见；
- persona 透传：Java PromptRegistry（messages[0] system）优先，DB 发布即生效；无 system 时内置兜底；
- 快慢模型路由：planner/reflector 走 AI_MODEL_FAST，reasoner 走主模型；
- 单次运行预算：超限提前收敛（不再调 LLM），trace 如实标注，不算 degraded；
- usage_total：按模型累计，混合来源时该模型槽位整体标 estimated（不伪装真实用量）。
"""
import asyncio

import pytest

from app import config
from app import graph as G
from app import prompts as P
from app import tools as T


# ---------- 差异化工具集 ----------

def test_tool_specs_filtered_by_agent_type():
    bi = {s["function"]["name"] for s in G.tool_specs("bi")}
    assert "create_purchase_order" not in bi
    assert "get_inventory" in bi and "list_suppliers" in bi
    contract = {s["function"]["name"] for s in G.tool_specs("contract")}
    assert "create_purchase_order" not in contract and "search_knowledge" in contract
    assert {s["function"]["name"] for s in G.tool_specs("general")} == set(G.TOOL_REGISTRY)
    replen = {s["function"]["name"] for s in G.tool_specs("replenishment")}
    assert "create_purchase_order" in replen  # 写工具保留，执行前仍过 HITL interrupt


def test_validate_calls_enforces_allowed_set():
    calls, dropped = G._validate_calls(
        [{"tool": "create_purchase_order",
          "args": {"supplier_id": 1, "sku_code": "SKU-X", "quantity": 1, "unit_price": 1.0}}],
        allowed=G._allowed_tools("bi"))
    assert calls == []
    assert "tool-not-allowed-for-agent" in " ".join(d["reason"] for d in dropped)


# ---------- persona 透传 + 模型路由 ----------

def test_persona_from_java_registry_wins(monkeypatch):
    captured = {}

    async def fake_chat(messages, tools=None, model=None):
        captured["system"] = messages[0]["content"]
        captured["model"] = model
        return {"text": "[]", "tool_calls": None, "provider": "stub"}

    monkeypatch.setattr(G, "chat", fake_chat)
    monkeypatch.setattr(config, "AI_MODEL_FAST", "fast-model-x")
    msgs = [{"role": "system", "content": "DB发布的人设Alpha版本"},
            {"role": "user", "content": "看下库存"}]
    out = asyncio.run(G.planner(G._fresh_state(msgs, "replenishment")))
    assert "DB发布的人设Alpha版本" in captured["system"]
    pv = out["prompt_versions"]
    assert pv["persona_source"] == "java-registry"
    assert captured["model"] == "fast-model-x"      # 规划走廉价档路由


def test_persona_builtin_fallback_and_versions_shape(monkeypatch):
    async def fake_chat(messages, tools=None, model=None):
        return {"text": "[]", "tool_calls": None, "provider": "stub"}

    monkeypatch.setattr(G, "chat", fake_chat)
    out = asyncio.run(G.planner(G._fresh_state([{"role": "user", "content": "hi"}], "bi")))
    pv = out["prompt_versions"]
    assert pv["persona_source"] == "builtin"
    assert pv["persona"] == P.persona_for("bi")[0]
    assert pv["planner"] == P.PLANNER[0] and pv["reflector"] == P.REFLECTOR[0]


# ---------- 单次运行预算 ----------

def test_budget_gate_stops_collecting(monkeypatch):
    state = G._fresh_state([{"role": "user", "content": "看下库存"}], "general")
    state["usage_total"] = {"prompt_tokens": 500, "completion_tokens": 100, "by_model": {}}
    monkeypatch.setattr(config, "RUN_TOKEN_BUDGET", 100)

    async def must_not_call(messages, tools=None, model=None):
        raise AssertionError("超预算后不应再调用 LLM")

    monkeypatch.setattr(G, "chat", must_not_call)
    out = asyncio.run(G.planner(state))
    assert out["budget_exhausted"] is True
    assert out["pending_tool_calls"] == []
    assert out["trace"][-1]["budget"] == {"limit": 100, "used": 600, "action": "stop_collecting"}
    # 预算收口是策略行为，不是故障：不算 degraded
    assert not out.get("degraded")


def test_budget_not_triggered_when_under_limit(monkeypatch):
    monkeypatch.setattr(config, "RUN_TOKEN_BUDGET", 100000)

    async def fake_chat(messages, tools=None, model=None):
        return {"text": "[]", "tool_calls": None, "provider": "stub"}

    monkeypatch.setattr(G, "chat", fake_chat)
    out = asyncio.run(G.planner(G._fresh_state([{"role": "user", "content": "hi"}], "general")))
    assert out.get("budget_exhausted") is False
    assert out["pending_tool_calls"] == []


def test_budget_per_agent_type_override():
    monkeypatch_config = getattr(config, "AGENT_TOKEN_BUDGETS", {}) or {}
    try:
        config.AGENT_TOKEN_BUDGETS = {"replenishment": 777}
        assert G._budget_for("replenishment") == 777
        assert G._budget_for("general") == int(getattr(config, "RUN_TOKEN_BUDGET", 0) or 0)
    finally:
        config.AGENT_TOKEN_BUDGETS = monkeypatch_config


def test_budget_disabled_by_default():
    assert G._budget_for("general") == 0  # 0=不限，零配置行为不变


# ---------- usage_total 按模型累计 ----------

def test_accumulate_usage_mixed_sources_mark_estimated():
    t = G._accumulate_usage({}, "fast-x", {"prompt_tokens": 10, "completion_tokens": 5, "source": "actual"})
    t = G._accumulate_usage(t, "fast-x", {"prompt_tokens": 3, "completion_tokens": 2, "source": "estimated"})
    assert t["prompt_tokens"] == 13 and t["completion_tokens"] == 7
    # 混合来源：该模型槽位整体如实标 estimated
    assert t["by_model"]["fast-x"]["source"] == "estimated"


def test_accumulate_usage_separates_models():
    t = G._accumulate_usage({}, "fast-x", {"prompt_tokens": 10, "completion_tokens": 5, "source": "actual"})
    t = G._accumulate_usage(t, "main-y", {"prompt_tokens": 100, "completion_tokens": 50, "source": "actual"})
    assert t["by_model"]["fast-x"]["prompt_tokens"] == 10
    assert t["by_model"]["main-y"]["prompt_tokens"] == 100
    assert t["prompt_tokens"] == 110


def test_run_reasoning_reports_platform_fields(monkeypatch):
    """端到端（mock provider + 假 Java 工具）：响应携带 usage_total/budget/prompt_versions。"""
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    monkeypatch.setattr(config, "RUN_TOKEN_BUDGET", 0)

    async def fake_call(path, method="GET", params=None, json=None, timeout_s=8.0):
        if path == "/api/inventory/low-stock":
            return [{"sku_code": "SKU-T001-WH-M", "spec": "白色/M", "warehouse": "华南仓",
                     "quantity": 120, "safety_stock": 200}]
        return {"error": f"no fake for {path}"}

    monkeypatch.setattr(T, "call_java_tool", fake_call)
    data = asyncio.run(G.run_reasoning_with_trace(
        [{"role": "user", "content": "哪些SKU低于安全库存"}], "general", "platform-t"))
    assert data["usage_total"]["prompt_tokens"] > 0
    assert data["usage_total"]["by_model"]  # 规划用量已按模型归档
    assert data["budget"] == {"limit": 0, "used": data["budget"]["used"], "exhausted": False}
    assert data["prompt_versions"]["planner"] == P.PLANNER[0]
