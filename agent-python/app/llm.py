"""LLM 适配层：真实 provider（muse /responses 与 OpenAI /chat/completions）+ Mock（仅离线演示）。

失败语义：真实模式下任何调用失败抛 LLMUnavailable，由 graph/main 转成显式 degraded 响应。
Mock provider 仅在未配置任何 API Key（LLM_MODE=mock）时启用，返回内容带 [Mock] 标记。

韧性（本层职责，不在 graph 内做）：
- 共享 httpx.AsyncClient 连接池（此前每次调用新建 client，握手开销与句柄浪费）；
- 瞬态失败（超时/连接错误/429/5xx）按 LLM_MAX_ATTEMPTS 指数退避重试，协议错误不重试；
- 非流式响应解析 provider usage 回填 actual token（缺失则不上报，由上层估算并标注 estimated）。

流式：chat_stream() 按 provider 原生 token delta 产出（openai stream=True / muse
/responses stream=true 的 output_text.delta 事件），graph.reasoner 在 SSE 请求下经
sink 逐片下发，前端收到真 token 流而非整段切块。
"""
from typing import List, Dict, Any, Optional, AsyncIterator
import asyncio
import json
import uuid
from contextvars import ContextVar
from . import config
import httpx

# opencode zen/go 网关要求：每次对话携带稳定会话头 x-opencode-session（路由/提示缓存优化），
# 缺失会被网关 400 拒绝（MissingSessionID）。会话粒度：main 中间件按 sessionId 设置，
# 直调/评测场景回退进程级稳定 ID（比"每请求一个"更利于缓存，比"无"正确）。
_opencode_session: ContextVar[str] = ContextVar("opencode_session", default=uuid.uuid4().hex)


def set_opencode_session(session_id: str) -> None:
    if session_id:
        _opencode_session.set(f"smartsupply-{session_id}"[:128])


def _opencode_headers() -> Dict[str, str]:
    """仅 opencode 网关需要的附加头（其他 OpenAI 兼容端点发未知头无意义）。"""
    return {"x-opencode-session": _opencode_session.get(),
            "User-Agent": "smartsupply-agent/1.0"}


class LLMUnavailable(RuntimeError):
    """真实模式下 LLM 不可用。携带 provider/model 供上层如实上报；retryable 标记瞬态失败。"""

    def __init__(self, message: str, provider: str = "openai", model: str = "", retryable: bool = False):
        super().__init__(message)
        self.provider = provider
        self.model = model
        self.retryable = retryable


_RETRYABLE_STATUS = (429, 500, 502, 503, 504)

# ---- 共享连接池（懒初始化；uvicorn 单事件循环内复用） ----
_shared_client: Optional[httpx.AsyncClient] = None


def _http_client() -> httpx.AsyncClient:
    global _shared_client
    if _shared_client is None or _shared_client.is_closed:
        _shared_client = httpx.AsyncClient(timeout=config.LLM_TIMEOUT_SECONDS)
    return _shared_client


def _mock_provider(messages: List[Dict[str, str]], tools: Optional[List[Dict[str, Any]]]) -> Dict[str, Any]:
    """离线 Mock。若被要求规划工具调用，按关键词返回 tool_calls —— provider 名如实标为
    mock，上层 trace/评测据此知道规划来自 Mock 规则而非模型，不作为模型能力计入。"""
    last = messages[-1]["content"] if messages else ""
    lower = last.lower()
    if tools:
        wants = {t["function"]["name"] for t in tools} if tools[0].get("type") == "function" else set(tools)
        choice = None
        if "已有工具结果" in last:  # reflector 形态：Mock 不再补调，直接收敛
            return {"text": "[]", "tool_calls": None, "provider": "mock"}
        if any(k in lower for k in ["库存", "补货", "sku", "低于"]):
            choice = "list_low_stock"
        elif any(k in lower for k in ["供应商"]):
            choice = "list_suppliers"
        elif any(k in lower for k in ["合同", "风控", "风险", "违约金"]):
            choice = "search_contracts"
        elif any(k in lower for k in ["知识", "规范", "条款", "检索"]):
            choice = "search_knowledge"
        elif any(k in lower for k in ["商品", "t恤", "托特", "箱包"]):
            choice = "search_catalog"
        if choice and choice in wants:
            args: Dict[str, Any] = {}
            if choice in ("search_contracts", "search_catalog", "search_knowledge"):
                for k in ["服装", "采购", "T恤", "帆布", "托特", "保温杯", "违约金", "风险", "风控",
                          "验收", "标准", "制度"]:
                    if k.lower() in lower:
                        args = {"keyword": k}
                        break
            return {"text": None, "tool_calls": [{"name": choice, "arguments": args}], "provider": "mock"}
        return {"text": "[]", "tool_calls": None, "provider": "mock"}

    if any(k in lower for k in ["合同", "风控", "风险"]):
        return {"text": "[Mock] 深度推理完成（合同风控分支）：检测到‘无限连带责任’属高风险，建议改为‘在乙方过错范围内承担有限责任’。",
                "tool_calls": None, "provider": "mock"}
    if any(k in lower for k in ["库存", "补货", "采购", "sku"]):
        return {"text": "[Mock] 深度推理完成（补货规划分支）：发现低于安全库存的 SKU，建议向合适供应商采购补货。",
                "tool_calls": None, "provider": "mock"}
    return {"text": f"[Mock] 已收到：{last}\n这是 Mock 回复，配置真实模型后由 {config.AI_MODEL} 驱动。",
            "tool_calls": None, "provider": "mock"}


def _muse_spec(model: Optional[str] = None) -> Optional[Dict[str, Any]]:
    model = model or config.AI_MODEL
    base = (config.OPENAI_BASE_URL or "https://opencode.ai/zen/go/v1").rstrip("/")
    if "opencode" in base and "muse" in (model or ""):
        return {"base": base, "key": config.OPENAI_API_KEY or config.DASHSCOPE_API_KEY,
                "model": model or "muse-spark-1.2-contributor"}
    return None


def _usage_from_muse(u: Dict[str, Any]) -> Dict[str, Any]:
    return {"prompt_tokens": int(u.get("input_tokens", u.get("prompt_tokens", 0)) or 0),
            "completion_tokens": int(u.get("output_tokens", u.get("completion_tokens", 0)) or 0),
            "source": "actual"}


def estimate_tokens(text: str) -> int:
    """本地粗估 token 数：CJK ≈ 1 token/字，其余 ≈ 4 字符/token。

    仅用于 provider 未回传 usage 时的观测兜底（Langfuse generation，
    source=estimated 显式标注）。精度低于 jtokkit 类真分词器，
    Java 台账的估算仍走 TokenEstimator(jtokkit)，本函数不进入计费口径。
    """
    if not text:
        return 0
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    return max(1, cjk + (len(text) - cjk + 3) // 4)


def usage_or_estimate(usage: Optional[Dict[str, Any]], prompt_text: str = "",
                      completion_text: str = "") -> Dict[str, Any]:
    """观测用量的统一来源：provider 真实回传（source=actual）优先，
    否则本地粗估并显式标注 source=estimated——绝不把估算伪装成真实用量。"""
    if isinstance(usage, dict) and usage.get("source"):
        return usage
    return {"prompt_tokens": estimate_tokens(prompt_text),
            "completion_tokens": estimate_tokens(completion_text),
            "source": "estimated"}


async def _muse_responses(messages: List[Dict[str, str]], tools: Optional[List[Dict[str, Any]]], spec: Dict[str, Any]) -> Dict[str, Any]:
    """muse Responses 协议：拼单条 input；工具规划时带 tools 并解析 function_call 输出项。"""
    parts = []
    for m in messages:
        parts.append(f"{m.get('role','user')}: {m.get('content','')}")
    input_text = "\n\n".join(parts).strip()
    payload: Dict[str, Any] = {"model": spec["model"], "input": input_text, "max_output_tokens": 2500,
                               "reasoning": {"effort": "low"}}
    if tools:
        payload["tools"] = [{"type": "function", "name": t["function"]["name"],
                             "description": t["function"].get("description", ""),
                             "parameters": t["function"].get("parameters", {})} for t in tools]
    try:
        r = await _http_client().post(f"{spec['base']}/responses",
                                      headers={"Authorization": f"Bearer {spec['key']}", "Content-Type": "application/json",
                                               **_opencode_headers()},
                                      json=payload)
        r.raise_for_status()
    except httpx.HTTPStatusError as e:
        code = e.response.status_code
        raise LLMUnavailable(f"muse /responses HTTP {code}: {e.response.text[:200]}",
                             provider="muse", model=spec["model"], retryable=code in _RETRYABLE_STATUS)
    except httpx.HTTPError as e:
        raise LLMUnavailable(f"muse /responses 调用失败: {e}", provider="muse", model=spec["model"], retryable=True)
    data = r.json()
    text_parts, tool_calls = [], []
    for node in data.get("output", []):
        if node.get("type") == "function_call":
            try:
                args = json.loads(node.get("arguments") or "{}")
            except Exception:
                args = {}
            tool_calls.append({"name": node.get("name", ""), "arguments": args})
        elif node.get("type") == "message" and node.get("role") == "assistant":
            for c in node.get("content", []):
                if c.get("type") == "output_text" and c.get("text"):
                    text_parts.append(c["text"])
    usage = data.get("usage")
    usage_out = _usage_from_muse(usage) if isinstance(usage, dict) else None
    if tool_calls:
        return {"text": None, "tool_calls": tool_calls, "provider": "muse", "usage": usage_out}
    if text_parts:
        return {"text": "\n".join(text_parts).strip(), "tool_calls": None, "provider": "muse", "usage": usage_out}
    if data.get("output_text"):
        return {"text": data["output_text"].strip(), "tool_calls": None, "provider": "muse", "usage": usage_out}
    if data.get("status") == "incomplete":
        raise LLMUnavailable("muse 推理截断（status=incomplete），请调大预算或重试", provider="muse", model=spec["model"])
    raise LLMUnavailable("muse 返回无内容", provider="muse", model=spec["model"])


async def _openai_chat(messages: List[Dict[str, str]], tools: Optional[List[Dict[str, Any]]], model: Optional[str] = None) -> Dict[str, Any]:
    try:
        import openai  # type: ignore
    except ImportError as e:
        raise LLMUnavailable(f"openai SDK 不可用: {e}", provider="openai", model=model or config.AI_MODEL)
    use_model = model or config.AI_MODEL
    extra_headers = _opencode_headers() if "opencode" in (config.OPENAI_BASE_URL or "") else {}
    try:
        client = openai.AsyncOpenAI(api_key=config.OPENAI_API_KEY or config.DASHSCOPE_API_KEY,
                                    base_url=config.OPENAI_BASE_URL, timeout=config.LLM_TIMEOUT_SECONDS,
                                    default_headers=extra_headers)
        kwargs: Dict[str, Any] = {"model": use_model, "messages": messages, "temperature": 0.3}
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"
        resp = await client.chat.completions.create(**kwargs)
    except Exception as e:
        retryable = _openai_retryable(e)
        raise LLMUnavailable(f"OpenAI 兼容接口调用失败: {e}", provider="openai", model=use_model, retryable=retryable)
    msg = resp.choices[0].message
    tool_calls = None
    if getattr(msg, "tool_calls", None):
        tool_calls = []
        for tc in msg.tool_calls:
            try:
                args = json.loads(tc.function.arguments or "{}")
            except Exception:
                args = {}
            tool_calls.append({"name": tc.function.name, "arguments": args})
    usage = getattr(resp, "usage", None)
    usage_out = None
    if usage is not None:
        usage_out = {"prompt_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
                     "completion_tokens": int(getattr(usage, "completion_tokens", 0) or 0),
                     "source": "actual"}
    return {"text": msg.content or "", "tool_calls": tool_calls, "provider": "openai", "usage": usage_out}


def _openai_retryable(e: Exception) -> bool:
    status = getattr(e, "status_code", None)
    if isinstance(status, int):
        return status in _RETRYABLE_STATUS
    return isinstance(e, (getattr(__import__("openai"), "APITimeoutError", ()),
                          getattr(__import__("openai"), "APIConnectionError", ())))


async def chat(messages: List[Dict[str, str]], tools: Optional[List[Dict[str, Any]]] = None,
               model: Optional[str] = None) -> Dict[str, Any]:
    """统一入口。返回 {"text", "tool_calls", "provider", "usage"?}；真实模式失败抛 LLMUnavailable。

    tools: OpenAI function-calling 格式的工具定义列表；给出时 provider 可返回结构化调用。
    model: 按调用路由模型（规划/反思走廉价档 AI_MODEL_FAST，作答走主模型）；缺省主模型。
    瞬态失败（超时/429/5xx）按指数退避自动重试，协议/内容类错误立即上抛。
    """
    if config.LLM_MODE == "mock":
        return _mock_provider(messages, tools)
    spec = _muse_spec(model)
    if spec:
        call = lambda: _muse_responses(messages, tools, spec)  # noqa: E731
    else:
        call = lambda: _openai_chat(messages, tools, model)  # noqa: E731
    backoff = max(0.1, config.LLM_BACKOFF_SECONDS)
    last: Optional[LLMUnavailable] = None
    for attempt in range(1, max(1, config.LLM_MAX_ATTEMPTS) + 1):
        try:
            return await call()
        except LLMUnavailable as e:
            last = e
            if not e.retryable or attempt >= config.LLM_MAX_ATTEMPTS:
                raise
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 8.0)
    raise last or LLMUnavailable("LLM call failed", retryable=False)


# ---- 真流式：按 provider 原生 token delta 产出 ----

async def chat_stream(messages: List[Dict[str, str]], tools: Optional[List[Dict[str, Any]]] = None,
                      usage_out: Optional[Dict[str, Any]] = None,
                      model: Optional[str] = None) -> AsyncIterator[str]:
    """逐 token delta 产出回答正文。仅用于最终回答生成（工具规划仍走非流式 chat）。
    usage_out 非空时，provider 返回真实用量则回填 {prompt_tokens, completion_tokens, source}。"""
    if config.LLM_MODE == "mock":
        res = _mock_provider(messages, None)
        text = res.get("text") or ""
        # Mock 按 24 字符切片产出多 delta，让离线链路覆盖真实的分片行为
        for i in range(0, len(text), 24):
            yield text[i:i + 24]
            await asyncio.sleep(0)
        return
    spec = _muse_spec(model)
    if spec:
        async for delta in _muse_stream(messages, spec, usage_out):
            yield delta
    else:
        async for delta in _openai_stream(messages, usage_out, model):
            yield delta


async def _muse_stream(messages: List[Dict[str, str]], spec: Dict[str, Any], usage_out: Optional[Dict[str, Any]]) -> AsyncIterator[str]:
    parts = []
    for m in messages:
        parts.append(f"{m.get('role','user')}: {m.get('content','')}")
    payload: Dict[str, Any] = {"model": spec["model"], "input": "\n\n".join(parts).strip(),
                               "max_output_tokens": 2500, "reasoning": {"effort": "low"}, "stream": True}
    try:
        async with _http_client().stream(
                "POST", f"{spec['base']}/responses",
                headers={"Authorization": f"Bearer {spec['key']}", "Content-Type": "application/json",
                         "Accept": "text/event-stream", **_opencode_headers()},
                json=payload) as r:
            if r.status_code != 200:
                body = (await r.aread()).decode("utf-8", "replace")[:200]
                raise LLMUnavailable(f"muse /responses(stream) HTTP {r.status_code}: {body}",
                                     provider="muse", model=spec["model"], retryable=r.status_code in _RETRYABLE_STATUS)
            async for line in r.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                try:
                    node = json.loads(data)
                except Exception:
                    continue
                t = node.get("type", "")
                if t == "response.output_text.delta":
                    delta = node.get("delta") or ""
                    if delta:
                        yield delta
                elif t == "response.completed":
                    u = (node.get("response") or {}).get("usage")
                    if usage_out is not None and isinstance(u, dict) and u:
                        usage_out.update(_usage_from_muse(u))
                elif t in ("response.failed", "error"):
                    raise LLMUnavailable(f"muse stream {t}: {str(node)[:200]}",
                                         provider="muse", model=spec["model"])
    except httpx.HTTPError as e:
        raise LLMUnavailable(f"muse stream 调用失败: {e}", provider="muse", model=spec["model"], retryable=True)


async def _openai_stream(messages: List[Dict[str, str]], usage_out: Optional[Dict[str, Any]],
                         model: Optional[str] = None) -> AsyncIterator[str]:
    try:
        import openai  # type: ignore
    except ImportError as e:  # pragma: no cover
        raise LLMUnavailable(f"openai SDK 不可用: {e}", provider="openai", model=model or config.AI_MODEL)
    use_model = model or config.AI_MODEL
    extra_headers = _opencode_headers() if "opencode" in (config.OPENAI_BASE_URL or "") else {}
    try:
        client = openai.AsyncOpenAI(api_key=config.OPENAI_API_KEY or config.DASHSCOPE_API_KEY,
                                    base_url=config.OPENAI_BASE_URL, timeout=config.LLM_TIMEOUT_SECONDS,
                                    default_headers=extra_headers)
        stream = await client.chat.completions.create(
            model=use_model, messages=messages, temperature=0.3,
            stream=True, stream_options={"include_usage": True})
        async for chunk in stream:
            usage = getattr(chunk, "usage", None)
            if usage is not None and usage_out is not None:
                usage_out.update({"prompt_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
                                  "completion_tokens": int(getattr(usage, "completion_tokens", 0) or 0),
                                  "source": "actual"})
            choices = getattr(chunk, "choices", None)
            if choices:
                delta = choices[0].delta
                content = getattr(delta, "content", None)
                if content:
                    yield content
    except Exception as e:
        raise LLMUnavailable(f"OpenAI 兼容流式调用失败: {e}", provider="openai", model=use_model,
                             retryable=_openai_retryable(e))
