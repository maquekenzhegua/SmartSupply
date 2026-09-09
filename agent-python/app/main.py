from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Depends, HTTPException, Security
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import APIKeyHeader
from pydantic import BaseModel
from typing import List, Dict, Any, Optional
import json as _json
import time, uuid

from . import config
from .graph import run_reasoning, run_reasoning_with_trace, resume_reasoning, stream_reasoning_events, MAX_ITERS
from .llm import chat, set_opencode_session
from .tools import TOOL_DEFS
from .rerank import rerank as py_rerank


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # 启动即声明安全态势：未配置 X-Api-Key 时 /api/reason* 对可达方完全开放，
    # 且无用户 token 透传的工具回环将 fail-closed（服务账号须显式配置）。
    # 内网可信假设必须显式成立，而不是悄悄裸奔。
    if not config.SIDECAR_API_KEY:
        import logging
        logging.getLogger("uvicorn.error").warning(
            "SIDECAR_API_KEY 未配置：/api/reason* 无边车鉴权，任何可达 8001 端口的一方都可驱动 agent；"
            "生产/共享网络环境必须配置该密钥（Java 侧同配 AGENT_PYTHON_API_KEY）")
    yield
    # 优雅关闭：checkpointer 走 Postgres 时释放连接池（MemorySaver 时为 no-op）
    from . import checkpointing
    await checkpointing.aclose()


app = FastAPI(title="SmartSupply LangGraph Sidecar", version="2.1.0", lifespan=lifespan)

# ---- 边车自身鉴权：配置 SIDECAR_API_KEY 后，推理端点要求 X-Api-Key 匹配 ----
# 此前 /api/reason* 完全无鉴权，任何可达 8001 端口的一方都能驱动 agent 并借边车回环调用 Java。
# Java 侧经 AGENT_PYTHON_API_KEY 配置同一密钥；未配置时保持本地开发零配置可用。
_api_key_header = APIKeyHeader(name="X-Api-Key", auto_error=False)


async def require_api_key(x_api_key: str = Security(_api_key_header)) -> None:
    expected = config.SIDECAR_API_KEY
    if expected and x_api_key != expected:
        raise HTTPException(status_code=401, detail="invalid api key")


@app.middleware("http")
async def add_trace(request: Request, call_next):
    trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex[:16]
    user_role = request.headers.get("X-User-Role") or ""
    # Java /chat 调用本服务时透传真实用户 JWT：回环工具以此身份调用 Java，
    # 身份与审计不再依赖一个未文档化的服务账号环境变量
    delegated = request.headers.get("Authorization") or ""
    if delegated.startswith("Bearer "):
        delegated = delegated[7:]
    # propagate to tools contextvar
    try:
        from .tools import set_trace_context
        set_trace_context(trace_id, user_role, delegated)
    except Exception:
        pass
    request.state.trace_id = trace_id
    response = await call_next(request)
    response.headers["X-Trace-Id"] = trace_id
    return response


class ReasonRequest(BaseModel):
    messages: List[Dict[str, str]]
    agentType: str = "general"
    sessionId: str = "default"
    # HITL 恢复：threadId + resume（{"approved": bool}）同时出现时恢复挂起的写闸门，
    # messages 忽略（图状态从 checkpoint 恢复）
    threadId: str = ""
    resume: Optional[Dict[str, Any]] = None


class RecallRequest(BaseModel):
    query: str


@app.get("/health")
async def health():
    from . import prompts
    return {"status": "ok", "ts": int(time.time()), "mode": "langgraph-react", "maxIters": MAX_ITERS,
            "tools": [t["name"] for t in TOOL_DEFS],
            "promptVersions": {"planner": prompts.PLANNER[0], "reflector": prompts.REFLECTOR[0]},
            "modelRouting": {"main": config.AI_MODEL, "fast": config.AI_MODEL_FAST},
            "runTokenBudget": config.RUN_TOKEN_BUDGET,
            "auth": {"sidecarApiKeyConfigured": bool(config.SIDECAR_API_KEY),
                     "serviceAccountConfigured": bool(config.JAVA_JWT_TOKEN or (config.SIDECAR_USERNAME and config.SIDECAR_PASSWORD))}}


@app.get("/tools")
async def tools():
    return {"tools": TOOL_DEFS}


@app.post("/api/reason", dependencies=[Depends(require_api_key)])
async def reason(req: ReasonRequest, request: Request):
    set_opencode_session(req.sessionId)
    try:
        if req.resume is not None and req.threadId:
            data = await resume_reasoning(req.threadId, req.resume, req.agentType, req.sessionId)
        else:
            data = await run_reasoning_with_trace(req.messages, req.agentType, req.sessionId)
        # snake_case fallback for Java side；degraded 让 Java 端能区分"推理完成"与"降级产物"；
        # usage 为 provider 真实用量（source=actual），缺失时 Java 侧回退估算并标 estimated；
        # usage_total 为全节点（planner/reflector/reasoner）按模型累计的用量与成本口径来源；
        # budget/prompt_versions 支撑成本预算与提示词版本归因；
        # interrupted=True 表示写闸门挂起等人工批准，confirm 为待批准动作，thread_id 用于恢复
        return {"reply": data["reply"], "agentType": req.agentType, "sessionId": req.sessionId,
                "trace": data["trace"], "tool_results": data["tool_results"], "toolResults": data["tool_results"],
                "iters": data["iters"], "mode": "langgraph-react",
                "degraded": data["degraded"], "degrade_reason": data["degrade_reason"],
                "usage": data.get("usage") or {},
                "usage_total": data.get("usage_total") or {},
                "budget": data.get("budget") or {},
                "prompt_versions": data.get("prompt_versions") or {},
                "threadId": data.get("thread_id") or "",
                "interrupted": bool(data.get("interrupted")),
                "confirm": data.get("confirm") or []}
    except Exception as e:
        # 图执行整体异常：显式 degraded，Java 端据此降级 java-direct 或报错，绝不与成功响应混淆
        return JSONResponse(status_code=500, content={"error": str(e), "degraded": True, "fallback": True})


@app.post("/api/reason/stream", dependencies=[Depends(require_api_key)])
async def reason_stream(req: ReasonRequest, request: Request):
    """深度模式 SSE：按节点实时下发 plan/tool/reflect/reply_delta/done 事件。

    Java 端逐事件转发给前端；reply_delta 为 provider 原生 token 增量（真流式），
    兼容旧调用方仍保留整段 reply 事件。写闸门挂起时下发 confirm_required 事件 +
    done(interrupted=true, thread_id)，调用方携 resume 恢复。"""
    set_opencode_session(req.sessionId)
    async def gen():
        try:
            if req.resume is not None and req.threadId:
                iterator = stream_reasoning_events(req.messages, req.agentType, req.sessionId,
                                                   thread_id=req.threadId, resume=req.resume)
            else:
                iterator = stream_reasoning_events(req.messages, req.agentType, req.sessionId)
            async for ev in iterator:
                yield f"event: {ev.get('event', 'message')}\ndata: {_json.dumps(ev, ensure_ascii=False, default=str)}\n\n"
        except Exception as e:  # 生成器本身异常也要以 SSE done 收口，避免连接悬挂
            yield "event: done\ndata: " + _json.dumps(
                {"degraded": True, "degrade_reason": f"stream_error: {e}"}, ensure_ascii=False) + "\n\n"
    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/api/chat", dependencies=[Depends(require_api_key)])
async def chat_simple(req: ReasonRequest):
    from .llm import LLMUnavailable
    try:
        res = await chat(req.messages)
    except LLMUnavailable as e:
        return JSONResponse(status_code=502, content={"error": str(e), "degraded": True})
    return {"reply": res.get("text") or "", "provider": res.get("provider"),
            "agentType": req.agentType, "sessionId": req.sessionId}


class RerankRequest(BaseModel):
    query: str
    docs: List[Dict[str, Any]]
    topK: int = 4
    mode: str = "auto"


@app.post("/api/rag/recall")
async def rag_recall(req: RecallRequest):
    """真实召回：转发 Java /api/knowledge/recall（pgvector + 重排）。
    此前是返回固定文案的占位假接口——调用方会把它当真召回结果使用，已改为诚实转发：
    Java 不可达时返回 502 + degraded，而非编造 context。"""
    from .tools import call_java_tool
    try:
        data = await call_java_tool("/api/knowledge/recall", "POST", json={"query": req.query}, timeout_s=10.0)
    except Exception as e:
        return JSONResponse(status_code=502, content={"query": req.query, "error": f"Java 服务不可达: {e}", "degraded": True})
    if isinstance(data, dict) and "error" in data:
        return JSONResponse(status_code=502, content={"query": req.query, "error": data["error"], "degraded": True})
    if isinstance(data, dict):
        return {"query": req.query, "context": data.get("context", ""), "citations": data.get("citations", []),
                "vectorHits": data.get("vectorHits"), "reranked": data.get("reranked")}
    return JSONResponse(status_code=502, content={"query": req.query, "error": "召回接口返回无法解析", "degraded": True})


@app.post("/api/rag/rerank")
async def rag_rerank(req: RerankRequest):
    try:
        data = py_rerank(req.query, req.docs, top_k=req.topK, mode=req.mode)
        return {"query": req.query, "mode": data.get("mode"), "fallback": data.get("fallback", False), "reranked": data.get("reranked", []), "scores": data.get("scores", [])}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.get("/")
async def root():
    return {"service": "agent-python", "docs": "/docs", "health": "/health", "reason": "POST /api/reason"}
