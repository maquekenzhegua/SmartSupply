"""端到端 Agent 轨迹评测（真实代码路径）。

此前本文件用 `mock_tool_choice = expected_tool` 自比自证，测的是断言自身而非系统，
属于假评测，已重构：直接驱动 app.graph 的完整 ReAct 循环（Mock provider 规划 +
monkeypatch 的 Java 回环数据），校验实际执行的工具、trace 结构与失败降级语义。
"""
import asyncio
import json
import pathlib

import pytest

from app import config
from app import graph as G
from app import tools as T

GOLDEN = pathlib.Path(__file__).with_name("golden_rag.jsonl")

# 以 Java 真实端点形态造的回环数据（路径→响应），供 call_java_tool 替身消费
FAVA_JAVA = {
    "/api/inventory/low-stock": [{"sku_code": "SKU-T001-WH-M", "spec": "白色/M", "warehouse": "华南仓",
                                  "quantity": 120, "safety_stock": 200}],
    "/api/inventory/by-sku": {"found": True, "sku_code": "SKU-T001-WH-M",
                              "records": [{"quantity": 120, "safety_stock": 200, "below_safety": True}]},
    "/api/suppliers": {"rows": [{"id": 1, "name": "测试供应商甲", "rating": 4.5}]},
    "/api/contracts": [{"id": 1, "title": "2026年度T恤采购框架合同", "status": "REVIEWING", "amount": 280000}],
    "/api/contracts/1/risk-report": {"contract_id": 1, "risk_level": "HIGH", "issues": ["无限连带责任"]},
    "/api/products/catalog/search": [{"product_name": "定制纯棉T恤", "sku_code": "SKU-T001-WH-M", "spec": "白色/M"}],
}


def _patch_java(monkeypatch, fail_paths=()):
    async def fake(path, method="GET", params=None, json=None, timeout_s=8.0):
        base = path.split("?")[0]
        for pref in FAVA_JAVA:
            if base == pref or base.startswith("/api/contracts/") and pref == "/api/contracts/1/risk-report":
                if base in fail_paths:
                    return {"error": f"Java API 403: forbidden on {base}", "auth_failed": True}
                return FAVA_JAVA[pref]
        return {"error": f"no fake for {path}"}

    monkeypatch.setattr(T, "call_java_tool", fake)


def _run(monkeypatch, question: str, fail_paths=()):
    monkeypatch.setattr(config, "LLM_MODE", "mock")
    _patch_java(monkeypatch, fail_paths)
    return asyncio.run(G.run_reasoning_with_trace([{"role": "user", "content": question}]))


def _tools_used(result) -> list:
    return [r["tool"] for r in result["tool_results"]]


# ---------- 轨迹级工具选择 ----------

@pytest.mark.parametrize("question,expected", [
    ("哪些SKU低于安全库存？", "list_low_stock"),
    ("帮我查一下服装采购合同的风险", "search_contracts"),
    ("搜索 T恤 商品", "search_catalog"),
    ("查一下供应商列表", "list_suppliers"),
])
def test_trajectory_selects_expected_tool(monkeypatch, question, expected):
    result = _run(monkeypatch, question)
    assert expected in _tools_used(result), f"轨迹 {result['trace']} 未执行 {expected}"
    assert not result["degraded"]
    assert result["reply"]  # mock 组织语言也有真实产出


def test_trajectory_tool_actually_executed_with_ok_flag(monkeypatch):
    result = _run(monkeypatch, "哪些SKU低于安全库存？")
    r = next(x for x in result["tool_results"] if x["tool"] == "list_low_stock")
    assert r["ok"] is True
    assert r["result"]["data"][0]["sku_code"] == "SKU-T001-WH-M"
    tool_steps = [t for t in result["trace"] if t.get("node") == "tool"]
    assert tool_steps and tool_steps[-1]["ok"] is True  # trace 如实记录成败


def test_trajectory_honest_when_java_down(monkeypatch):
    """全部取证失败 → 明确降级 + 失败原因入答案，绝不返回编造业务数据。"""
    async def boom(path, method="GET", params=None, json=None, timeout_s=8.0):
        raise RuntimeError("connection refused")

    monkeypatch.setattr(config, "LLM_MODE", "mock")
    monkeypatch.setattr(T, "call_java_tool", boom)
    result = asyncio.run(G.run_reasoning_with_trace([{"role": "user", "content": "哪些SKU低于安全库存？"}]))
    assert result["degraded"] is True
    assert result["degrade_reason"] == "all_tool_calls_failed"
    assert "connection refused" in result["reply"]
    assert "120" not in result["reply"]  # 没有编造库存数字


def test_trace_structure_and_provider_honesty(monkeypatch):
    result = _run(monkeypatch, "哪些SKU低于安全库存？")
    nodes = [t.get("node") for t in result["trace"]]
    assert "planner" in nodes and "tool" in nodes and nodes[-1] != "planner"
    plan_entry = next(t for t in result["trace"] if t["node"] == "planner")
    assert plan_entry["provider"] == "mock"  # Mock 规划如实标注，不被当成模型能力


# ---------- golden 数据集结构约束（非模型自证） ----------

def _rows():
    return [json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]


def test_injection_samples_present():
    rows = _rows()
    inj = [r for r in rows if any(k in r["question"] for k in ["Ignore", "System:", "忽略", "DROP", "注入"])]
    assert len(inj) >= 5


def test_hitl_samples_present():
    rows = _rows()
    hitl = [r for r in rows if "确认" in r.get("ground_truth", "") or "DRAFT" in r.get("ground_truth", "")]
    assert len(hitl) >= 5


# ---------- 真实模型规划（CI 无 Key 自动跳过） ----------

@pytest.mark.real_llm
@pytest.mark.skipif(
    not __import__("os").getenv("EVAL_REAL_LLM", ""),
    reason="set EVAL_REAL_LLM=1 + OPENAI_API_KEY 以用真实 LLM 验证 function-calling 规划",
)
def test_real_llm_trajectory_planning():
    import os  # noqa: F401
    result = asyncio.run(G.run_reasoning_with_trace(
        [{"role": "user", "content": "哪些SKU低于安全库存？该找哪个供应商补货？"}], "general"))
    used = _tools_used(result)
    assert "list_low_stock" in used
    assert "list_suppliers" in used  # 真实模型应做多步取证（规划→观察→反思补调）
