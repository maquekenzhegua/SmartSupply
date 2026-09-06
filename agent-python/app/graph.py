"""LangGraph 状态图：ReAct 多步循环 — 规划 -> 工具执行 -> (写操作人工闸门) -> 反思 -> 回答。

实现契约：
- 工具规划只走两条诚实路径：原生 function-calling（provider 返回 tool_calls），或解析
  模型输出的 JSON 文本。两者都失败即视为"无工具调用"，不再用关键词规则在图内伪装规划；
  离线 Mock 的关键词选择放在 llm._mock_provider（provider 标注为 mock，评测可甄别）。
- 工具失败/LLM 失败不再被吞掉：以 ok=False 信封与 degraded 标记向上传播到 API 响应。
- 同一批规划出的工具调用 asyncio.gather 并行执行（此前串行 for 循环，多工具取证延迟叠加）。
- SSE 场景下 reasoner 经 _reply_sink 逐 token 下发（llm.chat_stream 原生流式），
  非流式路径行为不变。
- HITL 写闸门（主流 interrupt/resume 模式）：写工具（create_purchase_order）在图内
  write_tools 节点执行前经 langgraph interrupt() 挂起，把待批准动作抛给调用方；
  调用方携 Command(resume={"approved": ...}) + 同一 thread_id 恢复执行。
  checkpointer 经 checkpointing 工厂选择：缺省进程内 MemorySaver（演示/单机/测试），
  配 CHECKPOINT_URI 时持久化到 Postgres（重启/多实例后挂起的写确认仍可恢复）。
  每次运行使用独立 thread_id，恢复时复用中断时下发的同一个，避免线程状态互相污染。
- 可观测性：Langfuse（可选）——一次运行一条 trace，thread_id 作关联键；
  未配置 LANGFUSE_* 时 observability 整体 no-op，零依赖零开销。
"""
from typing import TypedDict, List, Dict, Any, Literal, Optional, Callable
from contextvars import ContextVar
import asyncio
import json
import re
import uuid
from langgraph.graph import StateGraph, END
from langgraph.types import interrupt, Command

from . import config
from . import checkpointing
from . import observability as obs
from .llm import chat, chat_stream, usage_or_estimate, LLMUnavailable
from .tools import (
    tool_list_low_stock, tool_get_inventory, tool_list_suppliers,
    tool_search_contracts, tool_get_contract_risk, tool_search_catalog,
    tool_search_knowledge, tool_create_purchase_order,
)

MAX_ITERS = 6

# 写工具集合：图内单独节点执行，执行前 interrupt() 挂起等人工批准（HITL 决策层）。
# 执行层的权限/幂等/校验仍在 Java 可信层（ADMIN 角色 + 幂等键 + DRAFT 状态）。
WRITE_TOOLS = {"create_purchase_order"}

TOOL_REGISTRY: Dict[str, Any] = {
    "list_low_stock": tool_list_low_stock,
    "get_inventory": tool_get_inventory,
    "list_suppliers": tool_list_suppliers,
    "search_contracts": tool_search_contracts,
    "get_contract_risk": tool_get_contract_risk,
    "search_catalog": tool_search_catalog,
    "search_knowledge": tool_search_knowledge,
    "create_purchase_order": tool_create_purchase_order,
}

# 参数 schema：function-calling 的工具定义 + 校验/纠正模型给出的参数，一处声明两处使用
TOOL_SCHEMAS: Dict[str, Dict[str, Any]] = {
    "list_low_stock": {"description": "查询所有低于安全库存的SKU", "properties": {}},
    "get_inventory": {"description": "查询单个SKU的库存与安全库存",
                      "properties": {"sku_code": {"type": "string", "description": "SKU编码，如 SKU-T001-WH-M"}},
                      "required": ["sku_code"]},
    "list_suppliers": {"description": "查询供应商列表", "properties": {}},
    "search_contracts": {"description": "按关键词搜索合同",
                         "properties": {"keyword": {"type": "string", "description": "合同标题关键词"}},
                         "required": ["keyword"]},
    "get_contract_risk": {"description": "查询合同风控报告",
                          "properties": {"contract_id": {"type": "integer", "description": "合同ID"}},
                          "required": ["contract_id"]},
    "search_catalog": {"description": "搜索商品与SKU",
                       "properties": {"keyword": {"type": "string", "description": "商品关键词"}},
                       "required": ["keyword"]},
    "search_knowledge": {"description": "知识库语义检索：合同风控规范/制度条款/历史案例，返回带来源引用的 context",
                         "properties": {"keyword": {"type": "string", "description": "检索问题或关键词"}},
                         "required": ["keyword"]},
    "create_purchase_order": {"description": "创建采购单（DRAFT，执行前系统会挂起等待人工批准）。需指定供应商ID、SKU编码、数量、单价",
                              "properties": {"supplier_id": {"type": "integer", "description": "供应商ID"},
                                             "sku_code": {"type": "string", "description": "SKU编码，如 SKU-T001-WH-M"},
                                             "quantity": {"type": "integer", "description": "采购数量"},
                                             "unit_price": {"type": "number", "description": "单价（元）"}},
                              "required": ["supplier_id", "sku_code", "quantity", "unit_price"]},
}


def tool_specs() -> List[Dict[str, Any]]:
    """OpenAI function-calling 格式的工具定义，供 planner/reflector 传给 LLM。"""
    return [{"type": "function", "function": {
        "name": name,
        "description": s["description"],
        "parameters": {"type": "object", "properties": s["properties"], "required": s.get("required", [])},
    }} for name, s in TOOL_SCHEMAS.items()]


def _validate_calls(raw_calls: List[Dict[str, Any]]) -> tuple:
    """校验/纠正模型给出的工具名与参数。返回 (合法调用列表, 被丢弃项说明)。
    非法工具名、缺必填参数直接丢弃；int 参数尽力转换（"3"->3），转换失败丢弃。
    绝不伪造默认参数值（旧实现把坏 contract_id 静默改成 1，会把错误对象说成正确结论）。"""
    valid, dropped = [], []
    for c in raw_calls or []:
        if not isinstance(c, dict):
            dropped.append({"call": str(c)[:120], "reason": "not-object"})
            continue
        name = c.get("tool") or c.get("name")
        if name not in TOOL_REGISTRY:
            dropped.append({"call": str(name)[:60], "reason": "unknown-tool"})
            continue
        schema = TOOL_SCHEMAS[name]
        args_in = c.get("args") if isinstance(c.get("args"), dict) else c.get("arguments")
        args_in = args_in if isinstance(args_in, dict) else {}
        args: Dict[str, Any] = {}
        bad = None
        for key in schema.get("required", []):
            spec = schema["properties"].get(key, {})
            val = args_in.get(key)
            if val is None or val == "":
                bad = f"missing-required:{key}"
                break
            want = spec.get("type")
            if want == "integer":
                try:
                    val = int(val)
                except (TypeError, ValueError):
                    bad = f"bad-integer:{key}={val}"
                    break
            elif want == "number":
                try:
                    val = float(val)
                except (TypeError, ValueError):
                    bad = f"bad-number:{key}={val}"
                    break
            elif want == "string":
                val = str(val)
            args[key] = val
        if bad:
            dropped.append({"call": name, "reason": bad})
            continue
        valid.append({"tool": name, "args": args})
    return valid[:4], dropped


def _extract_json_array(text: str) -> Optional[List[Any]]:
    m = re.search(r"\[.*\]", text, re.DOTALL)
    if not m:
        return None
    try:
        parsed = json.loads(m.group(0))
        return parsed if isinstance(parsed, list) else None
    except Exception:
        return None


class AgentState(TypedDict, total=False):
    messages: List[Dict[str, str]]
    agent_type: str
    trace: List[Dict[str, Any]]
    iters: int
    pending_tool_calls: List[Dict[str, Any]]
    pending_write_calls: List[Dict[str, Any]]
    tool_results: List[Dict[str, Any]]
    final: str
    degraded: bool
    degrade_reason: str
    usage: Dict[str, Any]


SYSTEM_PLANNER = """你是供应链 ReAct 规划器。基于用户最新消息与已有工具结果决定下一步取证动作。
优先通过 function-calling 返回工具调用；判断无需工具或信息已足够时，输出 []。
每次最多选择 4 个互补的工具调用，参数必须来自用户消息或已有结果，不得臆造。
涉及制度/规范/条款类问题优先用 search_knowledge 检索知识库。
用户明确要求创建采购单/下单时规划 create_purchase_order，参数需完整（供应商ID/SKU/数量/单价）；
该工具执行前系统会自动挂起等待人工批准，无需额外确认动作。"""

REFLECT_SYSTEM = """你是反思器。检查已有工具结果是否足以回答用户问题。
若缺关键信息，通过 function-calling 补调工具（不得重复已成功的调用）；若足够，输出 []。
"""

# SSE 回答流式下沉点：stream_reasoning_events 设置，reasoner 检测到即走真流式
_reply_sink: ContextVar[Optional[Callable[[str], None]]] = ContextVar("reply_sink", default=None)

# Langfuse 当前 trace（一次运行一条）：run/stream 入口设置，节点内据此发 span/generation
_obs_trace: ContextVar[Optional[Any]] = ContextVar("obs_trace", default=None)


async def _plan_calls(prompt_system: str, task_hint: str, history: List[Dict[str, Any]]) -> tuple:
    """planner/reflector 共用：向 LLM 要结构化调用。返回 (calls, provider, error)。"""
    reflector_mode = bool(history)
    planner_messages = [
        {"role": "system", "content": prompt_system},
        {"role": "user", "content": task_hint + (
            f"\n已有工具结果：{json.dumps(history, ensure_ascii=False, default=str)[:4000]}" if history else "")},
    ]
    try:
        res = await chat(planner_messages, tools=tool_specs())
    except LLMUnavailable as e:
        return [], "unavailable", str(e)
    except Exception as e:  # provider 协议外异常同样如实上报，绝不伪装成规划成功
        return [], "error", str(e)
    provider = res.get("provider", "")
    calls_raw = res.get("tool_calls")
    if calls_raw is None:
        text = (res.get("text") or "").strip()
        calls_raw = _extract_json_array(text) or []
        if reflector_mode and text and not text.startswith("["):
            # 反思器输出非 JSON 时无法甄别其意图，按"足够"收敛（保守：不补调），如实记录
            provider += "+unparsed"
    calls, dropped = _validate_calls([c for c in calls_raw if isinstance(c, dict)])
    note = "dropped:" + json.dumps(dropped, ensure_ascii=False, default=str)[:300] if dropped else ""
    # 规划/反思本身也是 LLM 调用：以 generation 记入 Langfuse（禁用时 no-op）。
    # 此前漏传 usage 导致 trace 里 planner/reflector 永远无 token 数，即使 provider 回传了
    obs.generation(_obs_trace.get(), "reflector-llm" if reflector_mode else "planner-llm",
                   config.AI_MODEL, task_hint, {"calls": calls, "note": note},
                   usage=usage_or_estimate(
                       res.get("usage"),
                       "\n".join(str(m.get("content") or "") for m in planner_messages),
                       json.dumps(calls, ensure_ascii=False)))
    return calls, provider, note


async def planner(state: AgentState) -> Dict[str, Any]:
    iters = int(state.get("iters") or 0)
    if iters >= MAX_ITERS:
        return {"pending_tool_calls": []}
    trace = list(state.get("trace") or [])
    msgs = list(state.get("messages") or [])
    last = msgs[-1]["content"] if msgs else ""
    history = state.get("tool_results") or []
    agent_type = state.get("agent_type") or "general"

    task_hint = f"agent_type={agent_type}\n用户消息：{last}"
    calls, provider, note = await _plan_calls(SYSTEM_PLANNER, task_hint, history)
    entry: Dict[str, Any] = {"node": "planner", "provider": provider, "calls": calls}
    if note:
        entry["note"] = note
    trace.append(entry)
    update: Dict[str, Any] = {"pending_tool_calls": calls, "trace": trace, "iters": iters + 1}
    if provider in ("unavailable", "error"):
        update.update({"degraded": True, "degrade_reason": f"规划阶段 LLM 不可用: {note or provider}"})
    return update


async def tool_node(state: AgentState) -> Dict[str, Any]:
    """只读工具：同批并行取证（互相独立、只读），结果按调用顺序落位。
    写工具不在此执行——分流到 pending_write_calls，由 write_tools 节点串行过 HITL 闸门，
    避免 interrupt 挂起恢复时已完成的只读取证被整节点重放。"""
    calls: List[Dict[str, Any]] = state.get("pending_tool_calls") or []
    tool_results = list(state.get("tool_results") or [])
    trace = list(state.get("trace") or [])
    trace_parent = _obs_trace.get()

    read_calls = [c for c in calls if c["tool"] not in WRITE_TOOLS]
    write_calls = [c for c in calls if c["tool"] in WRITE_TOOLS]

    async def exec_one(c: Dict[str, Any]) -> Dict[str, Any]:
        name = c["tool"]
        args = c.get("args") or {}
        fn = TOOL_REGISTRY[name]
        try:
            if name == "get_inventory":
                res = await fn(args.get("sku_code", ""))
            elif name == "get_contract_risk":
                res = await fn(args["contract_id"])
            elif name in ("search_contracts", "search_catalog", "search_knowledge"):
                res = await fn(args.get("keyword", ""))
            else:
                res = await fn()
        except Exception as e:
            res = {"tool": name, "ok": False, "error": str(e), "auth_failed": False}
        ok = bool(res.get("ok")) if isinstance(res, dict) else True
        return {"tool": name, "args": args, "ok": ok, "result": res}

    # 同批调用并行取证（互相独立、只读），结果仍按调用顺序落位
    executed: List[Dict[str, Any]] = list(await asyncio.gather(*(exec_one(c) for c in read_calls))) if read_calls else []
    for r in executed:
        tool_results.append(r)
        entry: Dict[str, Any] = {"node": "tool", "tool": r["tool"], "args": r["args"], "ok": r["ok"]}
        if not r["ok"] and isinstance(r["result"], dict):
            entry["error"] = str(r["result"].get("error"))[:200]
        trace.append(entry)
        obs.span(trace_parent, f"tool:{r['tool']}", input={"args": r["args"]},
                 output={"ok": r["ok"]}, metadata={"node": "tools"})
    return {"tool_results": tool_results, "trace": trace, "pending_tool_calls": [],
            "pending_write_calls": write_calls}


async def write_tools(state: AgentState) -> Dict[str, Any]:
    """写工具 HITL 闸门（主流 interrupt/resume 模式）：每个写调用执行前 interrupt() 挂起，
    由调用方携 Command(resume={"approved": bool}) 恢复。批准才执行，拒绝如实记录
    （不伪造成功、也不算 degraded——按用户意志取消是合法结局）。
    节点独立于只读 tools 节点：恢复重放只发生在本节点，已完成取证不重复执行。"""
    calls: List[Dict[str, Any]] = state.get("pending_write_calls") or []
    tool_results = list(state.get("tool_results") or [])
    trace = list(state.get("trace") or [])
    trace_parent = _obs_trace.get()

    for c in calls:
        name = c["tool"]
        args = c.get("args") or {}
        approval: Any = interrupt({
            "type": "write_confirm",
            "tool": name,
            "args": args,
            "question": f"Agent 请求执行写操作 {name}（创建 DRAFT 采购单），请批准或拒绝。",
        })
        approved = bool(isinstance(approval, dict) and approval.get("approved"))
        if not approved:
            res = {"tool": name, "ok": False, "user_rejected": True,
                   "error": "用户拒绝执行该写操作（HITL 未批准）"}
            tool_results.append({"tool": name, "args": args, "ok": False, "result": res})
            trace.append({"node": "write_tool", "tool": name, "args": args, "ok": False, "rejected": True})
            obs.span(trace_parent, f"write:{name}", input={"args": args},
                     output={"approved": False}, metadata={"node": "write_tools"})
            continue
        fn = TOOL_REGISTRY[name]
        try:
            res = await fn(**args)
        except Exception as e:
            res = {"tool": name, "ok": False, "error": str(e), "auth_failed": False}
        ok = bool(res.get("ok")) if isinstance(res, dict) else True
        tool_results.append({"tool": name, "args": args, "ok": ok, "result": res})
        entry: Dict[str, Any] = {"node": "write_tool", "tool": name, "args": args, "ok": ok}
        if not ok and isinstance(res, dict):
            entry["error"] = str(res.get("error"))[:200]
        trace.append(entry)
        obs.span(trace_parent, f"write:{name}", input={"args": args},
                 output={"ok": ok, "result": res if not ok else None}, metadata={"node": "write_tools"})
    return {"tool_results": tool_results, "trace": trace,
            "pending_tool_calls": [], "pending_write_calls": []}


async def reflector(state: AgentState) -> Dict[str, Any]:
    iters = int(state.get("iters") or 0)
    if iters >= MAX_ITERS:
        return {"pending_tool_calls": []}
    trace = list(state.get("trace") or [])
    msgs = list(state.get("messages") or [])
    last = msgs[-1]["content"] if msgs else ""
    tool_results = state.get("tool_results") or []

    done = {(r.get("tool"), json.dumps(r.get("args"), ensure_ascii=False, sort_keys=True, default=str)) for r in tool_results}
    calls, provider, note = await _plan_calls(
        REFLECT_SYSTEM, f"用户消息：{last}",
        [{"tool": r.get("tool"), "args": r.get("args"), "ok": r.get("ok")} for r in tool_results])
    filtered = [c for c in calls
                if (c["tool"], json.dumps(c["args"], ensure_ascii=False, sort_keys=True)) not in done]
    entry: Dict[str, Any] = {"node": "reflector", "provider": provider, "calls": filtered[:3]}
    if note:
        entry["note"] = note
    trace.append(entry)
    obs.span(_obs_trace.get(), "reflector", input={"tool_results": len(tool_results)},
             output={"calls": filtered[:3], "provider": provider}, metadata={"node": "reflector"})
    update: Dict[str, Any] = {"pending_tool_calls": filtered[:3], "trace": trace}
    if provider in ("unavailable", "error"):
        update.update({"degraded": True, "degrade_reason": f"反思阶段 LLM 不可用: {note or provider}"})
    return update


def _digest_results(tool_results: List[Dict[str, Any]], max_chars: int = 6000) -> str:
    """序列化成功结果并截断到预算内：按条目累加，超限即停并如实标注省略数量，
    而非整包 str 截断把最后一条 JSON 腰斩成坏数据。"""
    ok_items = [r for r in tool_results if r.get("ok")]
    parts, used, omitted = [], 0, 0
    for r in ok_items:
        s = json.dumps({"tool": r["tool"], "args": r["args"], "data": r["result"].get("data")}, ensure_ascii=False, default=str)
        if used + len(s) > max_chars:
            omitted += 1
            continue
        parts.append(s)
        used += len(s)
    text = "\n".join(parts)
    if omitted:
        text += f"\n[另有 {omitted} 条成功结果因上下文预算被省略]"
    return text


async def reasoner(state: AgentState) -> Dict[str, Any]:
    msgs = list(state["messages"])
    tool_results = state.get("tool_results") or []
    trace = list(state.get("trace") or [])
    failures = [r for r in tool_results if not r.get("ok")]
    successes = [r for r in tool_results if r.get("ok")]

    if tool_results and not successes:
        # 全部取证失败：不向 LLM 喂空证据求"看起来像答案"的输出，直接如实报告
        rejected = [r for r in failures if isinstance(r.get("result"), dict) and r["result"].get("user_rejected")]
        if rejected and len(rejected) == len(failures):
            # 全部失败均为"用户拒绝"：合法结局而非系统故障，不计 degraded
            names = "、".join(r["tool"] for r in rejected)
            return {"final": f"已取消：写操作（{names}）未被批准执行，未对系统做任何变更。如需继续，请重新发起并在确认框中批准。",
                    "trace": trace}
        fail_lines = "\n".join(f"- {r['tool']}: {str(r['result'].get('error'))[:160]}" for r in failures)
        return {"final": f"无法完成本次查询：所有数据源调用均失败，未获得任何可信业务数据。\n{fail_lines}\n请稍后重试，或检查 Java 服务与账号权限。",
                "degraded": True, "degrade_reason": "all_tool_calls_failed", "trace": trace}

    extra = []
    used_knowledge = any(r.get("tool") == "search_knowledge" for r in successes)
    if successes:
        guidance = ("以下是可信工具执行结果（已通过权限校验的只读查询），请基于它们作答，要求："
                    "1) 引用关键数据 2) 低于安全库存才建议补货 3) 合同风控要标风险等级与修改建议。"
                    "未包含的字段如实说明。")
        if used_knowledge:
            guidance += "其中 search_knowledge 返回的 context 来自知识库检索，作答请依据其内容并在结尾标注来源文档标题。"
        extra.append(guidance + "\n" + _digest_results(successes))
    if failures:
        extra.append("注意：以下取证调用失败了，涉及的部分请如实声明'未能查询到/数据源暂不可用'，不得编造：\n" + "\n".join(
            f"- {r['tool']}({json.dumps(r['args'], ensure_ascii=False, default=str)}): {str(r['result'].get('error'))[:160]}" for r in failures))
    if extra:
        msgs = msgs + [{"role": "system", "content": "\n\n".join(extra)}]

    sink = _reply_sink.get()
    if sink is not None:
        # SSE 路径：逐 token 下发，同时累计完整回答；usage 经 usage_box 回传
        usage_box: Dict[str, Any] = {}
        parts: List[str] = []
        try:
            async for delta in chat_stream(msgs, usage_out=usage_box):
                parts.append(delta)
                sink(delta)
        except LLMUnavailable as e:
            # 组织语言阶段失败：仍给出已取证的真实数据，不降级为 Mock 冒充模型输出
            digest = _digest_results(successes, max_chars=2000)
            fallback = f"（语言模型暂不可用：{e}。以下为工具直查结果，未经模型组织）\n{digest or '无可用的工具结果。'}"
            sink(fallback)
            return {"final": fallback, "degraded": True, "degrade_reason": f"reasoner_llm_unavailable: {e}", "trace": trace}
        update: Dict[str, Any] = {"final": "".join(parts), "trace": trace}
        if usage_box:
            update["usage"] = usage_box
        obs.generation(_obs_trace.get(), "reasoner", config.AI_MODEL,
                       {"messages": len(msgs)}, update["final"],
                       usage=usage_or_estimate(usage_box,
                                               "\n".join(str(m.get("content") or "") for m in msgs),
                                               update["final"]))
        return update

    try:
        reply = await chat(msgs)
    except LLMUnavailable as e:
        digest = _digest_results(successes, max_chars=2000)
        return {"final": f"（语言模型暂不可用：{e}。以下为工具直查结果，未经模型组织）\n{digest or '无可用的工具结果。'}",
                "degraded": True, "degrade_reason": f"reasoner_llm_unavailable: {e}", "trace": trace}
    update = {"final": reply.get("text") or "", "trace": trace}
    if reply.get("usage"):
        update["usage"] = reply["usage"]
    obs.generation(_obs_trace.get(), "reasoner", config.AI_MODEL,
                   {"messages": len(msgs)}, update["final"],
                   usage=usage_or_estimate(reply.get("usage"),
                                           "\n".join(str(m.get("content") or "") for m in msgs),
                                           update["final"]))
    return update


def should_continue_after_reflect(state: AgentState) -> Literal["tools", "reason"]:
    calls = state.get("pending_tool_calls") or []
    iters = int(state.get("iters") or 0)
    if calls and iters < MAX_ITERS:
        return "tools"
    return "reason"


def _route_after_tools(state: AgentState) -> Literal["write_tools", "reflector"]:
    """只读取证完成后：有待执行写调用 → 写闸门（可能 interrupt 挂起）；否则进反思。"""
    return "write_tools" if (state.get("pending_write_calls")) else "reflector"


def build_graph(checkpointer):
    g = StateGraph(AgentState)
    g.add_node("planner", planner)
    g.add_node("tools", tool_node)
    g.add_node("write_tools", write_tools)
    g.add_node("reflector", reflector)
    g.add_node("reasoner", reasoner)
    g.set_entry_point("planner")
    g.add_conditional_edges("planner", lambda s: "tools" if (s.get("pending_tool_calls")) else "reasoner")
    g.add_conditional_edges("tools", _route_after_tools, {"write_tools": "write_tools", "reflector": "reflector"})
    g.add_edge("write_tools", "reflector")
    g.add_conditional_edges("reflector", should_continue_after_reflect, {"tools": "tools", "reason": "reasoner"})
    g.add_edge("reasoner", END)
    return g.compile(checkpointer=checkpointer)


# 惰性单例：checkpointer 实现依赖 CHECKPOINT_URI，且 AsyncPostgresSaver 构造需要运行中的
# event loop（内部 asyncio.Lock 绑定 loop），因此延迟到首个请求协程内再构建。
_graph = None


async def get_graph():
    global _graph
    if _graph is None:
        _graph = build_graph(await checkpointing.get_checkpointer())
    return _graph

def _fresh_state(messages: List[Dict[str, str]], agent_type: str) -> Dict[str, Any]:
    """每次调用独立初始状态（不可共享可变默认值，避免并发串号）。"""
    return {"messages": list(messages), "agent_type": agent_type, "tool_results": [], "trace": [],
            "iters": 0, "pending_tool_calls": [], "pending_write_calls": [], "final": "",
            "degraded": False, "degrade_reason": "", "usage": {}}


def new_thread_id(session_id: str = "") -> str:
    """thread 生命周期 = 单次 agent 运行：interrupt 恢复复用同一个，其余一律新建。"""
    return f"{session_id or 'anon'}:{uuid.uuid4().hex[:8]}"


def _extract_interrupts(values: Dict[str, Any]) -> List[Dict[str, Any]]:
    """从图返回值中提取挂起的写确认载荷（ainterrupt 结果含 __interrupt__ 键）。"""
    out: List[Dict[str, Any]] = []
    for intr in values.get("__interrupt__") or ():
        val = getattr(intr, "value", None)
        if isinstance(val, dict):
            out.append(val)
    return out


async def run_reasoning(messages: List[Dict[str, str]], agent_type: str = "general") -> str:
    g = await get_graph()
    result = await g.ainvoke(_fresh_state(messages, agent_type),
                             config={"configurable": {"thread_id": new_thread_id()}})
    return result.get("final", "")


async def run_reasoning_with_trace(messages: List[Dict[str, str]], agent_type: str = "general",
                                   session_id: str = "") -> Dict[str, Any]:
    """非流式入口。被写闸门挂起时返回 interrupted=True + confirm 载荷 + thread_id，
    调用方（Java/前端）凭这三样发起人工批准并以 resume_reasoning 恢复。"""
    tid = new_thread_id(session_id)
    state = _fresh_state(messages, agent_type)
    trace_obj = obs.start_trace(tid, session_id, agent_type,
                                (messages[-1]["content"] if messages else "")[:500])
    token = _obs_trace.set(trace_obj)
    try:
        result = await (await get_graph()).ainvoke(dict(state), config={"configurable": {"thread_id": tid}})
    finally:
        _obs_trace.reset(token)
    if result.get("__interrupt__"):
        confirm = _extract_interrupts(result)
        return {"reply": "", "trace": result.get("trace") or [], "tool_results": result.get("tool_results") or [],
                "iters": int(result.get("iters") or 0), "degraded": False, "degrade_reason": "",
                "usage": {}, "interrupted": True, "confirm": confirm, "thread_id": tid}
    return {"reply": result.get("final", ""), "trace": result.get("trace") or [],
            "tool_results": result.get("tool_results") or [], "iters": int(result.get("iters") or 0),
            "degraded": bool(result.get("degraded")), "degrade_reason": result.get("degrade_reason") or "",
            "usage": result.get("usage") or {}, "interrupted": False, "thread_id": tid}


async def resume_reasoning(thread_id: str, resume_value: Any, agent_type: str = "general",
                           session_id: str = "") -> Dict[str, Any]:
    """携 Command(resume) 恢复挂起的写闸门；返回结构与 run_reasoning_with_trace 一致。"""
    cfg = {"configurable": {"thread_id": thread_id}}
    g = await get_graph()
    snap = await g.aget_state(cfg)
    if not (snap.next or (snap.values or {}).get("__interrupt__")):
        # 无挂起中断（边车重启丢 checkpoint / 重复恢复）：如实报错，绝不伪造执行成功
        return {"reply": "", "trace": [], "tool_results": [], "iters": 0, "degraded": True,
                "degrade_reason": "no_pending_interrupt: 挂起状态不存在（可能边车已重启），请重新发起请求",
                "usage": {}, "interrupted": False, "thread_id": thread_id}
    state = snap.values or {}
    trace_obj = obs.start_trace(thread_id, session_id, str(state.get("agent_type") or agent_type),
                                str((state.get("messages") or [{}])[-1].get("content", ""))[:500])
    token = _obs_trace.set(trace_obj)
    try:
        result = await g.ainvoke(Command(resume=resume_value), config=cfg)
    finally:
        _obs_trace.reset(token)
    if result.get("__interrupt__"):
        # 连续多个写调用：批准了第一个，第二个又挂起
        return {"reply": "", "trace": result.get("trace") or [], "tool_results": result.get("tool_results") or [],
                "iters": int(result.get("iters") or 0), "degraded": False, "degrade_reason": "",
                "usage": {}, "interrupted": True, "confirm": _extract_interrupts(result), "thread_id": thread_id}
    return {"reply": result.get("final", ""), "trace": result.get("trace") or [],
            "tool_results": result.get("tool_results") or [], "iters": int(result.get("iters") or 0),
            "degraded": bool(result.get("degraded")), "degrade_reason": result.get("degrade_reason") or "",
            "usage": result.get("usage") or {}, "interrupted": False, "thread_id": thread_id}


async def stream_reasoning_events(messages: List[Dict[str, str]], agent_type: str = "general",
                                  session_id: str = "", thread_id: str = "", resume: Any = None):
    """深度模式事件流：graph 每个节点完成后即产出事件；reasoner 回答按 provider token
    delta 以 reply_delta 事件实时流出（经 contextvar sink 桥接到消费队列）。

    事件序列（顺序即真实执行序）：
      plan             规划决策 {calls, provider}
      tool             每次只读取证 {tool, args, ok, error?}
      write_tool       写操作结果 {tool, args, ok, rejected?}（仅在人工批准/拒绝后产生）
      reflect          反思决策 {calls, provider}
      confirm_required 写闸门挂起 {tool, args, question} + thread_id（等待 Command(resume) 恢复）
      reply_delta      回答增量 {text}（provider 原生分片；mock 为切片模拟）
      done             收尾汇总 {degraded, degrade_reason, iters, tools, usage?, interrupted?, thread_id}
    degraded 语义与 run_reasoning_with_trace 完全一致；图执行异常也以 done(degraded) 收口。
    resume 语义：resume is not None 且 thread_id 非空 → 恢复该 thread 的挂起中断（messages 忽略）。
    """
    resuming = resume is not None and bool(thread_id)
    cfg = {"configurable": {"thread_id": thread_id}}
    g = await get_graph()
    if resuming:
        snap = await g.aget_state(cfg)
        if not (snap.next or (snap.values or {}).get("__interrupt__")):
            yield {"event": "done", "degraded": True,
                   "degrade_reason": "no_pending_interrupt: 挂起状态不存在（可能边车已重启），请重新发起请求",
                   "iters": 0, "tools": [], "thread_id": thread_id, "interrupted": False}
            return
        state: Dict[str, Any] = dict(snap.values or {})
        state.pop("__interrupt__", None)
        graph_input: Any = Command(resume=resume)
    else:
        tid = thread_id or new_thread_id(session_id)
        cfg = {"configurable": {"thread_id": tid}}
        state = _fresh_state(messages, agent_type)
        graph_input = dict(state)
        thread_id = tid
    queue: asyncio.Queue = asyncio.Queue()
    emitted_tools = 0
    emitted_writes = len(state.get("tool_results") or [])
    reply_emitted = 0
    interrupted = False
    confirm_payloads: List[Dict[str, Any]] = []

    def _sink(text: str) -> None:
        nonlocal reply_emitted
        reply_emitted += 1
        queue.put_nowait({"event": "reply_delta", "text": text})

    trace_obj = obs.start_trace(thread_id, session_id, str(state.get("agent_type") or agent_type),
                                str((state.get("messages") or [{}])[-1].get("content", ""))[:500])
    obs_token = _obs_trace.set(trace_obj)

    async def _drive() -> None:
        nonlocal emitted_tools, emitted_writes, reply_emitted, interrupted
        try:
            async for chunk in g.astream(graph_input, config=cfg, stream_mode="updates"):
                if "__interrupt__" in (chunk or {}):
                    for intr in chunk["__interrupt__"] or ():
                        val = getattr(intr, "value", None)
                        if isinstance(val, dict):
                            interrupted = True
                            confirm_payloads.append(val)
                            queue.put_nowait({"event": "confirm_required", "thread_id": thread_id,
                                              "tool": val.get("tool"), "args": val.get("args"),
                                              "question": val.get("question")})
                    continue
                for node, update in (chunk or {}).items():
                    if not isinstance(update, dict):
                        continue
                    state.update(update)
                    if node == "planner":
                        trace = update.get("trace") or []
                        queue.put_nowait({"event": "plan", "calls": update.get("pending_tool_calls") or [],
                                          "provider": str(trace[-1].get("provider", "")) if trace else ""})
                    elif node == "tools":
                        results = update.get("tool_results") or []
                        for r in results[emitted_tools:]:
                            ev = {"event": "tool", "tool": r.get("tool"), "args": r.get("args"), "ok": r.get("ok")}
                            if not r.get("ok") and isinstance(r.get("result"), dict):
                                ev["error"] = str(r["result"].get("error"))[:200]
                            queue.put_nowait(ev)
                        emitted_tools = len(results)
                    elif node == "write_tools":
                        results = update.get("tool_results") or []
                        for r in results[emitted_writes:]:
                            ev = {"event": "write_tool", "tool": r.get("tool"), "args": r.get("args"), "ok": r.get("ok")}
                            if isinstance(r.get("result"), dict) and r["result"].get("user_rejected"):
                                ev["rejected"] = True
                            if not r.get("ok") and isinstance(r.get("result"), dict):
                                ev["error"] = str(r["result"].get("error"))[:200]
                            queue.put_nowait(ev)
                        emitted_writes = len(results)
                    elif node == "reflector":
                        trace = update.get("trace") or []
                        queue.put_nowait({"event": "reflect", "calls": update.get("pending_tool_calls") or [],
                                          "provider": str(trace[-1].get("provider", "")) if trace else ""})
                    elif node == "reasoner":
                        # 未走流式的收口路径（如全部取证失败/全部被拒绝）整段补发，
                        # 保证 SSE 消费方在任何分支都能拿到回答正文
                        final = update.get("final") or ""
                        if final and reply_emitted == 0:
                            queue.put_nowait({"event": "reply_delta", "text": final})
                    # 正文经 _reply_sink 以 reply_delta 流出，节点完成事件不重复发整段
        except Exception as e:  # 图执行中途异常：已发事件保留，收尾如实报 degraded
            queue.put_nowait({"__error__": str(e)})
        finally:
            queue.put_nowait(None)

    _reply_sink.set(_sink)  # 必须在 driver task 创建前设置：task 继承创建时的 contextvars 快照
    driver = asyncio.create_task(_drive())
    try:
        while True:
            item = await queue.get()
            if item is None:
                break
            if "__error__" in item:
                yield {"event": "done", "degraded": True, "degrade_reason": f"graph_error: {item['__error__']}",
                       "iters": int(state.get("iters") or 0),
                       "tools": [t.get("tool") for t in (state.get("tool_results") or [])],
                       "thread_id": thread_id, "interrupted": False}
                await driver
                return
            yield item
    finally:
        _obs_trace.reset(obs_token)
        _reply_sink.set(None)
    await driver
    done: Dict[str, Any] = {"event": "done", "degraded": bool(state.get("degraded")),
                            "degrade_reason": state.get("degrade_reason") or "",
                            "iters": int(state.get("iters") or 0),
                            "tools": [t.get("tool") for t in (state.get("tool_results") or [])],
                            "thread_id": thread_id, "interrupted": interrupted}
    if state.get("usage"):
        done["usage"] = state.get("usage")
    if confirm_payloads:
        done["confirm"] = confirm_payloads
    yield done
    obs.finish_trace(trace_obj, output=str(state.get("final") or "")[:2000],
                     metadata={"interrupted": interrupted, "iters": done["iters"]})
