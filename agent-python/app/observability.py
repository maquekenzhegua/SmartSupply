"""Langfuse 可观测性埋点（可选依赖，失败静默降级为 no-op）。

设计约束：
- 未配置 LANGFUSE_PUBLIC_KEY/SECRET_KEY 时整体禁用：不产生网络/IO 开销，CI 与离线评测零依赖。
- 埋点绝不影响主流程：所有入口 try/except 吞异常（观测系统故障不能拖垮推理），
  只有"客户端构造失败"在首次使用时告警一次。
- Trace 语义：一次 agent 运行 = 一条 trace（session_id 分组）；planner/tools/reflector 为子 span，
  reasoner 为 generation（携带 provider 真实 token usage）。thread_id 写入 trace metadata，
  便于与 LangGraph checkpoint、Java agent_run 表三方关联。
- 版本矩阵：本模块面向 Langfuse Server v2（单容器轻量自部署）+ Python SDK 2.x 经典 API。
  Server v3（OTel 原生，需额外 ClickHouse/MinIO/Redis）对应 SDK 3.x——升级时只需改本模块，
  上述四个封装函数的签名与语义保持不变。
"""
import json
from typing import Any, Dict, Optional

from . import config

_client = None
_client_failed = False


def enabled() -> bool:
    return bool(config.LANGFUSE_PUBLIC_KEY and config.LANGFUSE_SECRET_KEY)


def client():
    """惰性单例。未配置 key 返回 None；SDK 构造失败只告警一次并永久禁用本进程埋点。"""
    global _client, _client_failed
    if _client_failed or not enabled():
        return None
    if _client is None:
        try:
            from langfuse import Langfuse
            _client = Langfuse(
                public_key=config.LANGFUSE_PUBLIC_KEY,
                secret_key=config.LANGFUSE_SECRET_KEY,
                host=config.LANGFUSE_HOST or "http://localhost:3000",
            )
        except Exception as e:
            logging.getLogger(__name__).warning("Langfuse 客户端构造失败，本次进程禁用埋点: %s", e)
            _client_failed = True
            return None
    return _client


def _dump(data: Any, limit: int = 4000) -> Any:
    """任意结构转 JSON 安全载荷（超长截断，default=str 兜底不可序列化对象）。"""
    try:
        s = json.dumps(data, ensure_ascii=False, default=str)
        return s[:limit] if len(s) > limit else data
    except Exception:
        return str(data)[:limit]


class _Noop:
    """禁用态的空实现：与真实 trace/span 同接口，调用方无需判空。"""

    def start_span(self, *a, **k): return self
    def start_generation(self, *a, **k): return self
    def span(self, *a, **k): return self
    def generation(self, *a, **k): return self
    def update(self, *a, **k): return None
    def update_trace(self, *a, **k): return None
    def end(self, *a, **k): return None


NOOP = _Noop()


def start_trace(thread_id: str, session_id: str, agent_type: str, question: str) -> Any:
    """一条 agent 运行开一条 trace；session_id 聚合会话，thread_id 进 metadata。"""
    lf = client()
    if lf is None:
        return NOOP
    try:
        return lf.trace(
            name="langgraph-agent-run",
            session_id=session_id or None,
            input=_dump({"agent_type": agent_type, "question": question}),
            metadata={"thread_id": thread_id, "session_id": session_id},
        )
    except Exception:
        return NOOP


def span(trace: Any, name: str, input: Any = None, output: Any = None,
         metadata: Any = None) -> Any:
    """子 span：planner/reflector 决策、单次工具取证。fire-and-forget：即建即闭，
    不留悬空 span（graph 事件到达时输出已定，无需跨作用域持有）。"""
    if trace is None:
        return None
    try:
        s = trace.span(name=name, input=_dump(input) if input is not None else None,
                       output=_dump(output) if output is not None else None,
                       metadata=_dump(metadata, 800) if metadata else None)
        s.end()
        return s
    except Exception:
        return None


def generation(trace: Any, name: str, model: str, input: Any, output: Any,
               usage: Optional[Dict[str, Any]] = None) -> Any:
    """reasoner 等 LLM 调用的 generation；usage 为 token 数。
    来源随 metadata.token_source 一并落 trace：actual=provider 真实回传，
    estimated=本地粗估（llm.usage_or_estimate），绝不混淆两种口径。"""
    if trace is None:
        return None
    try:
        details: Dict[str, int] = {}
        source = None
        if usage:
            pt, ct = int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0)
            if pt > 0:
                details["input"] = pt
            if ct > 0:
                details["output"] = ct
            source = usage.get("source")
        # 双格式：v2 服务端只解析 usage(ModelUsage)，usageDetails 是 v3 字段——
        # 两个都传，升级 v3 后无缝沿用
        usage_body = ({"input": details["input"], "output": details["output"],
                       "unit": "TOKENS"} if details else None)
        g = trace.generation(name=name, model=model or "unknown",
                             input=_dump(input), output=_dump(output),
                             usage=usage_body, usage_details=details or None,
                             metadata={"token_source": str(source)} if source else None)
        g.end()
        return g
    except Exception:
        return None


def finish_trace(trace: Any, output: Any = None, metadata: Any = None) -> None:
    if trace is None:
        return
    try:
        trace.update(output=_dump(output) if output is not None else None,
                     metadata=_dump(metadata, 800) if metadata else None)
        flush()
    except Exception:
        pass


def flush() -> None:
    lf = client()
    if lf is None:
        return
    try:
        lf.flush()
    except Exception:
        pass
