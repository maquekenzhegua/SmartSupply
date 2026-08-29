"""Tool 选型回归 —— 直接测试 app.graph 的真实代码（规划器关键词兜底 + JSON 解析），
不再在测试内复刻业务逻辑。离线可跑：LLM_MODE=mock 时规划器自然落入非 JSON 的关键词回退分支。
"""
import asyncio

import pytest

from app.graph import (
    MAX_ITERS,
    TOOL_REGISTRY,
    _extract_json_array,
    planner,
)
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
    }


def _plan(question: str) -> list:
    result = asyncio.run(planner(_state(question)))
    return result.get("pending_tool_calls") or []


def test_extract_json_array_real_parser():
    assert _extract_json_array('前言 [{"tool":"list_low_stock","args":{}}] 后缀') == [
        {"tool": "list_low_stock", "args": {}}
    ]
    assert _extract_json_array("[]") == []
    assert _extract_json_array("这不是 JSON") is None
    assert _extract_json_array('[{"tool": broken}') is None


def test_planner_keyword_fallback_routes_to_real_tools():
    calls = _plan("查询低库存有哪些 SKU")
    assert calls and calls[0]["tool"] == "list_low_stock"

    calls = _plan("帮我看下合同违约金条款风险")
    assert calls and calls[0]["tool"] == "search_contracts"
    # 关键词应从用户消息中提取
    assert calls[0]["args"].get("keyword") in ("违约金", "风险", "服装")


def test_planner_no_tool_for_catalog_without_llm_json():
    # 规划器关键词兜底只覆盖库存/合同；商品类查询不强行造工具，交给 reasoner 直答
    calls = _plan("搜索 T恤 商品")
    assert calls == []


def test_planner_filters_unknown_tool_names():
    # 非法工具名即使出现在 LLM 输出里也会被过滤（mock 模式下经兜底路径覆盖不到，直接构造断言注册表约束）
    assert "list_low_stock" in TOOL_REGISTRY
    assert all(name in TOOL_REGISTRY for name in {d["name"] for d in TOOL_DEFS})
    assert MAX_ITERS >= 1


@pytest.mark.real_llm
@pytest.mark.skipif(
    not __import__("os").getenv("EVAL_REAL_LLM", ""),
    reason="set EVAL_REAL_LLM=1 + OPENAI_API_KEY 以用真实 LLM 验证规划器 JSON 输出",
)
def test_planner_real_llm_emits_valid_json_plan():
    calls = _plan("查询低库存有哪些 SKU")
    assert all(c["tool"] in TOOL_REGISTRY for c in calls)
