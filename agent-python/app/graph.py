"""LangGraph 状态图：真 ReAct 多步循环 — 规划 -> 工具调度 -> 观察 -> 反思 -> 回答。"""
from typing import TypedDict, List, Dict, Any, Literal
import json
import re
from langgraph.graph import StateGraph, END

from .llm import chat
from .tools import (
    tool_list_low_stock, tool_get_inventory, tool_list_suppliers,
    tool_search_contracts, tool_get_contract_risk, tool_search_catalog,
)

MAX_ITERS = 6

TOOL_REGISTRY: Dict[str, Any] = {
    "list_low_stock": tool_list_low_stock,
    "get_inventory": tool_get_inventory,
    "list_suppliers": tool_list_suppliers,
    "search_contracts": tool_search_contracts,
    "get_contract_risk": tool_get_contract_risk,
    "search_catalog": tool_search_catalog,
}


def _extract_json_array(text: str):
    m = re.search(r"\[.*\]", text, re.DOTALL)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:
        return None


class AgentState(TypedDict, total=False):
    messages: List[Dict[str, str]]
    agent_type: str
    trace: List[Dict[str, Any]]
    iters: int
    pending_tool_calls: List[Dict[str, Any]]
    tool_results: List[Dict[str, Any]]
    final: str
    error: str


SYSTEM_PLANNER = """你是供应链 ReAct 规划器。基于用户最新消息、历史 tool_results 和 agent_type 决定下一步。
必须只输出 JSON 数组，形如：
[{"tool":"list_low_stock","args":{}}]
可用工具：
- list_low_stock {}  查询低于安全库存的SKU
- get_inventory {"sku_code":"SKU-T001-WH-M"}  查单SKU库存
- list_suppliers {}  查供应商列表
- search_contracts {"keyword":"服装"}  按关键词搜合同
- get_contract_risk {"contract_id":1}  查合同风控报告
- search_catalog {"keyword":"T恤"}  搜商品SKU
若无需工具或已收集足够信息，输出 []
只输出 JSON 数组，不要其他文字。
"""

REFLECT_SYSTEM = """你是反思器。检查 tool_results 是否足以回答用户问题。
若缺关键信息，输出 JSON 数组继续补调工具；若足够，输出 []。
同样只输出 JSON 数组。"""


async def planner(state: AgentState) -> Dict[str, Any]:
    iters = int(state.get("iters") or 0)
    if iters >= MAX_ITERS:
        return {"pending_tool_calls": []}
    trace = list(state.get("trace") or [])
    msgs = list(state.get("messages") or [])
    last = msgs[-1]["content"] if msgs else ""
    history = state.get("tool_results") or []
    agent_type = state.get("agent_type") or "general"

    hint = ""
    if agent_type == "contract" or any(k in last for k in ["合同", "风控", "风险", "违约金", "连带"]):
        hint = " 合同风控任务优先用 search_contracts/get_contract_risk。"
    elif any(k in last for k in ["库存", "补货", "SKU", "采购", "低于", "low stock", "inventory", "供应商"]):
        hint = " 库存/采购任务优先用 list_low_stock/get_inventory/list_suppliers。"
    elif any(k in last for k in ["商品", "T恤", "箱包", "catalog"]):
        hint = " 商品任务优先用 search_catalog。"

    planner_messages = [
        {"role": "system", "content": SYSTEM_PLANNER + hint},
        {"role": "user", "content": f"agent_type={agent_type}\n用户消息：{last}\n已有工具结果：{json.dumps(history, ensure_ascii=False)[:4000]}"},
    ]
    try:
        raw = await chat(planner_messages)
    except Exception as e:
        trace.append({"node": "planner", "error": str(e)})
        return {"pending_tool_calls": [], "trace": trace, "iters": iters + 1}

    calls = _extract_json_array(raw)
    if calls is None:
        text = (raw or "").strip()
        if text.startswith("[") and text.endswith("]"):
            try:
                calls = json.loads(text)
            except Exception:
                calls = []
        else:
            # 非 JSON 的规划：轻量关键词回退，保证无 LLM 额度时仍能触发工具
            if hint and "库存" in hint:
                calls = [{"tool": "list_low_stock", "args": {}}]
            elif "合同" in hint:
                kw = "服装"
                for k in ["服装", "采购", "T恤", "风险"]:
                    if k in last:
                        kw = k
                        break
                calls = [{"tool": "search_contracts", "args": {"keyword": kw}}]
            else:
                calls = []

    calls = [c for c in (calls or []) if isinstance(c, dict) and c.get("tool") in TOOL_REGISTRY]
    for c in calls:
        if "args" not in c or not isinstance(c["args"], dict):
            c["args"] = {}
    trace.append({"node": "planner", "raw": raw[:800], "calls": calls})
    return {"pending_tool_calls": calls[:4], "trace": trace, "iters": iters + 1}


async def tool_node(state: AgentState) -> Dict[str, Any]:
    calls: List[Dict[str, Any]] = state.get("pending_tool_calls") or []
    tool_results = list(state.get("tool_results") or [])
    trace = list(state.get("trace") or [])
    for c in calls:
        name = c["tool"]
        args = c.get("args") or {}
        fn = TOOL_REGISTRY[name]
        try:
            if name == "get_inventory":
                res = await fn(args.get("sku_code", ""))
            elif name == "search_contracts":
                res = await fn(args.get("keyword", ""))
            elif name == "get_contract_risk":
                cid = args.get("contract_id")
                try:
                    cid = int(cid)
                except Exception:
                    cid = 1
                res = await fn(cid)
            elif name == "search_catalog":
                res = await fn(args.get("keyword", ""))
            else:
                res = await fn()
        except Exception as e:
            res = {"error": str(e), "tool": name}
        tool_results.append({"tool": name, "args": args, "result": res})
        trace.append({"node": "tool", "tool": name, "args": args, "ok": "error" not in str(res)[:200]})
    return {"tool_results": tool_results, "trace": trace, "pending_tool_calls": []}


async def reflector(state: AgentState) -> Dict[str, Any]:
    iters = int(state.get("iters") or 0)
    if iters >= MAX_ITERS:
        return {"pending_tool_calls": []}
    trace = list(state.get("trace") or [])
    msgs = list(state.get("messages") or [])
    last = msgs[-1]["content"] if msgs else ""
    tool_results = state.get("tool_results") or []

    need_more = False
    has_low = any(r.get("tool") == "list_low_stock" for r in tool_results)
    has_supp = any(r.get("tool") == "list_suppliers" for r in tool_results)
    has_catalog = any(r.get("tool") == "search_catalog" for r in tool_results)
    has_contract = any(r.get("tool") in ("search_contracts", "get_contract_risk") for r in tool_results)

    if any(k in last for k in ["供应商", "采购", "下单", "create"]) and has_low and not has_supp:
        need_more = True
        reflect_hint = '需要供应商信息以决定采购对象，补调 list_suppliers。'
    elif any(k in last for k in ["合同", "风控", "风险"]) and not has_contract:
        need_more = True
        reflect_hint = '合同任务尚未检索合同。'
    elif any(k in last for k in ["商品", "T恤", "箱包"]) and not has_catalog:
        need_more = True
        reflect_hint = '商品任务尚未检索目录。'
    else:
        reflect_hint = '评估是否还需补调工具；若信息已充分则返回 []。'

    if not need_more and iters >= 2:
        return {"pending_tool_calls": [], "trace": trace}

    # 让 LLM 做反思（失败则用规则兜底）
    reflect_messages = [
        {"role": "system", "content": REFLECT_SYSTEM},
        {"role": "user", "content": f"用户消息：{last}\n已有工具结果：{json.dumps(tool_results, ensure_ascii=False)[:4000]}\n提示：{reflect_hint}"},
    ]
    try:
        raw = await chat(reflect_messages)
        calls = _extract_json_array(raw)
        if calls is None:
            calls = []
        calls = [c for c in calls if isinstance(c, dict) and c.get("tool") in TOOL_REGISTRY]
        for c in calls:
            if "args" not in c or not isinstance(c["args"], dict):
                c["args"] = {}
        # 避免重复已调
        done = {(r.get("tool"), json.dumps(r.get("args"), ensure_ascii=False, sort_keys=True)) for r in tool_results}
        filtered = [c for c in calls if (c["tool"], json.dumps(c.get("args"), ensure_ascii=False, sort_keys=True)) not in done]
        trace.append({"node": "reflector", "raw": raw[:800], "calls": filtered})
        if filtered:
            return {"pending_tool_calls": filtered[:3], "trace": trace, "iters": iters + 1}
        # 规则补调
        if need_more:
            if "供应商" in reflect_hint or "list_suppliers" in reflect_hint:
                trace.append({"node": "reflector_fallback", "calls": [{"tool": "list_suppliers"}]})
                return {"pending_tool_calls": [{"tool": "list_suppliers", "args": {}}], "trace": trace, "iters": iters + 1}
        return {"pending_tool_calls": [], "trace": trace}
    except Exception as e:
        trace.append({"node": "reflector_error", "error": str(e)})
        if need_more:
            return {"pending_tool_calls": [{"tool": "list_suppliers", "args": {}}], "trace": trace, "iters": iters + 1}
        return {"pending_tool_calls": [], "trace": trace}


async def reasoner(state: AgentState) -> Dict[str, Any]:
    msgs = list(state["messages"])
    tool_results = state.get("tool_results") or []
    trace = state.get("trace") or []
    if tool_results:
        evidence = json.dumps(tool_results, ensure_ascii=False)
        msgs = msgs + [{"role": "system", "content": f"以下是可信工具执行结果（已通过权限校验的只读查询/受控写入），请基于它们作答，要求：1) 引用关键数据 2) 低于安全库存才建议补货 3) 合同风控要标风险等级与修改建议。\n{evidence[:6000]}"}]
    if trace:
        msgs = msgs + [{"role": "system", "content": f"内部链路 trace 供自检（不向用户展示原始 trace）：{json.dumps(trace, ensure_ascii=False)[:3000]}"}]
    reply = await chat(msgs)
    return {"final": reply}


def should_continue_after_tools(state: AgentState) -> Literal["reflect", "reason"]:
    # 每次 tool 执行后都进反思，做 ReAct 的 observation->reflection
    return "reflect"


def should_continue_after_reflect(state: AgentState) -> Literal["tools", "reason"]:
    calls = state.get("pending_tool_calls") or []
    iters = int(state.get("iters") or 0)
    if calls and iters < MAX_ITERS:
        return "tools"
    return "reason"


def build_graph():
    g = StateGraph(AgentState)
    g.add_node("planner", planner)
    g.add_node("tools", tool_node)
    g.add_node("reflector", reflector)
    g.add_node("reasoner", reasoner)
    g.set_entry_point("planner")
    g.add_conditional_edges("planner", lambda s: "tools" if (s.get("pending_tool_calls")) else "reasoner")
    g.add_edge("tools", "reflector")
    g.add_conditional_edges("reflector", should_continue_after_reflect, {"tools": "tools", "reason": "reasoner"})
    g.add_edge("reasoner", END)
    return g.compile()


graph = build_graph()


async def run_reasoning(messages: List[Dict[str, str]], agent_type: str = "general") -> str:
    result = await graph.ainvoke({"messages": messages, "agent_type": agent_type, "tool_results": [], "trace": [], "iters": 0, "pending_tool_calls": [], "final": ""})
    return result.get("final", "")


async def run_reasoning_with_trace(messages: List[Dict[str, str]], agent_type: str = "general") -> Dict[str, Any]:
    result = await graph.ainvoke({"messages": messages, "agent_type": agent_type, "tool_results": [], "trace": [], "iters": 0, "pending_tool_calls": [], "final": ""})
    return {"reply": result.get("final", ""), "trace": result.get("trace") or [], "tool_results": result.get("tool_results") or [], "iters": int(result.get("iters") or 0)}
