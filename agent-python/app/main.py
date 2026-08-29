from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import List, Dict, Any
import time, uuid

from .graph import run_reasoning, run_reasoning_with_trace
from .llm import chat
from .tools import TOOL_DEFS
from .rerank import rerank as py_rerank

app = FastAPI(title="SmartSupply LangGraph Sidecar", version="2.0.0")


@app.middleware("http")
async def add_trace(request: Request, call_next):
    trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex[:16]
    response = await call_next(request)
    response.headers["X-Trace-Id"] = trace_id
    return response


class ReasonRequest(BaseModel):
    messages: List[Dict[str, str]]
    agentType: str = "general"
    sessionId: str = "default"


class RecallRequest(BaseModel):
    query: str


@app.get("/health")
async def health():
    return {"status": "ok", "ts": int(time.time()), "mode": "langgraph-react", "maxIters": 6, "tools": [t["name"] for t in TOOL_DEFS]}


@app.get("/tools")
async def tools():
    return {"tools": TOOL_DEFS}


@app.post("/api/reason")
async def reason(req: ReasonRequest):
    try:
        data = await run_reasoning_with_trace(req.messages, req.agentType)
        return {"reply": data["reply"], "agentType": req.agentType, "sessionId": req.sessionId, "trace": data["trace"], "toolResults": data["tool_results"], "iters": data["iters"], "mode": "langgraph-react"}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e), "fallback": True})


@app.post("/api/chat")
async def chat_simple(req: ReasonRequest):
    reply = await chat(req.messages)
    return {"reply": reply, "agentType": req.agentType, "sessionId": req.sessionId}


class RerankRequest(BaseModel):
    query: str
    docs: List[Dict[str, Any]]
    topK: int = 4
    mode: str = "auto"


@app.post("/api/rag/recall")
async def rag_recall(req: RecallRequest):
    return {"query": req.query, "context": f"[Python] 召回占位，实际由 Java pgvector 召回：{req.query}"}


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
