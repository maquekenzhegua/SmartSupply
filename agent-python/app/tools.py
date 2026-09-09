"""Python 侧对 Java Tool API 的回调封装 + LangGraph 工具调度。Java 是可信执行层，Python 只做决策。

诚实性契约（P0 修复）：任何失败路径（HTTP 4xx/5xx、鉴权、连接异常）一律返回
ok=False 的结构化错误，绝不返回伪造的 demo 业务数据。demo 降级曾是"评测/演示拿假数据
当真结果"的根源，已整体移除；需要离线演示时由 LLM_MODE=mock 提供（Mock 文本自带
[Mock] 前缀，不冒充业务事实）。
"""
import asyncio
import contextvars
import httpx
from typing import Any, Dict, List, Optional
from . import config

_trace_var: contextvars.ContextVar[str] = contextvars.ContextVar("trace_id", default="")
_role_var: contextvars.ContextVar[str] = contextvars.ContextVar("user_role", default="")
# 发起本次对话的真实用户 JWT（由 Java 经 Authorization 头透传），优先于服务账号 token 用于回环鉴权
_token_var: contextvars.ContextVar[str] = contextvars.ContextVar("delegated_token", default="")

# 各工具超时预算（秒）：列表/搜索类快，风控报告涉及文档解析慢一些
TOOL_TIMEOUT_S: Dict[str, float] = {
    "list_low_stock": 8, "get_inventory": 8, "list_suppliers": 8,
    "search_contracts": 8, "get_contract_risk": 15, "search_catalog": 8,
    "search_knowledge": 10, "create_purchase_order": 15,
}


def set_trace_context(trace_id: str, user_role: str = "", token: str = ""):
    if trace_id: _trace_var.set(trace_id)
    if user_role: _role_var.set(user_role)
    if token: _token_var.set(token)

def _auth_token() -> str:
    # 优先用透传的真实用户 JWT（身份/审计正确），否则回退服务账号 JAVA_JWT_TOKEN
    return _token_var.get("") or config.JAVA_JWT_TOKEN

# 服务账号兜底：无透传 token 且未配 JAVA_JWT_TOKEN 时，自动登录 config.SIDECAR_* 账号，
# 失效（401/403）自动刷新重试一次。让"边车直调 /api/reason"也开箱可用。
_svc_token: str = ""
_svc_token_lock = asyncio.Lock()

async def _ensure_service_token() -> str:
    global _svc_token
    if config.JAVA_JWT_TOKEN:
        return config.JAVA_JWT_TOKEN
    if _svc_token:
        return _svc_token
    if not (config.SIDECAR_USERNAME and config.SIDECAR_PASSWORD):
        # fail-closed：服务账号未显式配置时不再自动登录（旧默认 admin/admin123 已移除）。
        # 返回空 token → 回环请求以无鉴权发起，Java 返回 401 → ok=False 错误信封如实上报，
        # 绝不用弱默认口令悄悄换取高权限身份。
        return ""
    async with _svc_token_lock:
        if _svc_token:  # 双检：等锁期间可能已被刷新
            return _svc_token
        try:
            # trust_env=False：登录回环与工具回环同理，绝不走系统代理
            async with httpx.AsyncClient(timeout=10, trust_env=False) as client:
                r = await client.post(f"{config.JAVA_API_BASE.rstrip('/')}/api/auth/login",
                                      json={"username": config.SIDECAR_USERNAME, "password": config.SIDECAR_PASSWORD})
                if r.status_code == 200:
                    tok = (r.json().get("data") or {}).get("token", "")
                    if tok:
                        _svc_token = tok
                        return tok
        except Exception:
            pass
    return ""

def _headers(token: str = "") -> Dict[str, str]:
    h: Dict[str, str] = {}
    tok = token or _auth_token()
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    tid = _trace_var.get("")
    if tid: h["X-Trace-Id"] = tid
    return h


async def _java_request(client, method: str, url: str, params, json_body):
    """单次回环请求：用户透传 token 优先，缺失则取/刷新服务账号 token。"""
    tok = _auth_token()
    if not tok:
        tok = await _ensure_service_token()
    r = await client.request(method, url, params=params, json=json_body, headers=_headers(tok))
    if r.status_code in (401, 403) and not _token_var.get("") and not config.JAVA_JWT_TOKEN:
        # 服务账号 token 可能过期：清缓存重登一次再试
        global _svc_token
        _svc_token = ""
        tok = await _ensure_service_token()
        r = await client.request(method, url, params=params, json=json_body, headers=_headers(tok))
    return r

async def call_java_tool(path: str, method: str = "GET", params: Dict[str, Any] = None, json: Dict[str, Any] = None,
                         timeout_s: float = 8.0) -> Any:
    """调用 Java 只读 API。成功返回解包后的 data；任何非 200 返回 {"error", "auth_failed"}，
    连接层异常直接抛出（由 tool_* 统一转成 ok=False 错误信封）。
    trust_env=False：内网回环绝不经系统代理（Windows 上 httpx 会经 getproxies() 读
    WinINET 注册表代理，把 localhost:8080 送进代理后拿到裸 502——实跑踩坑）。"""
    url = f"{config.JAVA_API_BASE.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=timeout_s, trust_env=False) as client:
        r = await _java_request(client, method, url, params, json)
        if r.status_code != 200:
            return {"error": f"Java API {r.status_code}: {r.text[:500]}", "auth_failed": r.status_code in (401, 403)}
        try:
            data = r.json()
        except Exception:
            return {"raw": r.text[:1000]}
        if isinstance(data, dict) and "data" in data:
            return data["data"]
        return data


def _err(tool: str, message: str, auth_failed: bool = False) -> Dict[str, Any]:
    return {"tool": tool, "ok": False, "error": message, "auth_failed": auth_failed}

def _ok(tool: str, data: Any) -> Dict[str, Any]:
    return {"tool": tool, "ok": True, "error": None, "auth_failed": False, "data": data}

def _as_error_envelope(tool: str, data: Any) -> Optional[Dict[str, Any]]:
    """call_java_tool 返回错误 dict 时转成统一信封；否则返回 None 表示调用本身成功。"""
    if isinstance(data, dict) and "error" in data:
        return _err(tool, str(data["error"]), bool(data.get("auth_failed")))
    return None


async def tool_list_low_stock() -> Dict[str, Any]:
    name = "list_low_stock"
    try:
        data = await call_java_tool("/api/inventory/low-stock", "GET", timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    return _ok(name, data if isinstance(data, list) else [])


async def tool_get_inventory(sku_code: str) -> Dict[str, Any]:
    name = "get_inventory"
    try:
        data = await call_java_tool("/api/inventory/by-sku", "GET", params={"skuCode": sku_code},
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    if isinstance(data, dict):
        return _ok(name, data)
    return _ok(name, {"found": False, "sku_code": sku_code})


def _extract_rows(data: Any) -> Optional[List[Dict[str, Any]]]:
    """兼容 Java 分页信封（rows/records）与裸数组两种形态。"""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in ("rows", "records"):
            if isinstance(data.get(key), list):
                return data[key]
    return None


async def tool_list_suppliers() -> Dict[str, Any]:
    name = "list_suppliers"
    try:
        data = await call_java_tool("/api/suppliers", "GET", timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    rows = _extract_rows(data)
    if rows is None:
        return _err(name, "供应商接口返回了无法解析的结构")
    return _ok(name, rows)


async def tool_search_contracts(keyword: str) -> Dict[str, Any]:
    name = "search_contracts"
    try:
        data = await call_java_tool("/api/contracts", "GET", params={"keyword": keyword or ""},
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    rows = _extract_rows(data)
    if rows is None:
        return _err(name, "合同接口返回了无法解析的结构")
    kw = (keyword or "").lower()
    hits = [x for x in rows if kw in str(x.get("title", "")).lower()] if kw else rows
    return _ok(name, hits[:10])


async def tool_get_contract_risk(contract_id: int) -> Dict[str, Any]:
    name = "get_contract_risk"
    try:
        data = await call_java_tool(f"/api/contracts/{int(contract_id)}/risk-report", "GET",
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    return _ok(name, data)


async def tool_search_knowledge(keyword: str) -> Dict[str, Any]:
    """知识库语义取证：转发 Java /api/knowledge/recall（pgvector 召回 + 重排 + 引用）。
    让 LangGraph 深度推理模式具备 RAG 能力，回答可携带知识库来源而非仅靠工具数据。"""
    name = "search_knowledge"
    try:
        data = await call_java_tool("/api/knowledge/recall", "POST", json={"query": keyword or ""},
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    if isinstance(data, dict) and ("context" in data or "citations" in data):
        return _ok(name, {"context": data.get("context", ""),
                          "citations": data.get("citations") or [],
                          "vector_hits": data.get("vectorHits"),
                          "reranked": data.get("reranked")})
    return _err(name, "知识库召回接口返回了无法解析的结构")


async def tool_search_catalog(keyword: str) -> Dict[str, Any]:
    name = "search_catalog"
    try:
        data = await call_java_tool("/api/products/catalog/search", "GET", params={"keyword": keyword or ""},
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    rows = _extract_rows(data)
    if rows is None:
        return _err(name, "商品目录接口返回了无法解析的结构")
    return _ok(name, rows[:10])


async def tool_create_purchase_order(supplier_id: int, sku_code: str, quantity: int, unit_price: float) -> Dict[str, Any]:
    """写工具（唯一）：回调 Java /api/agent/purchase-orders 创建 DRAFT 采购单。
    纵深防线分工：LangGraph 图内 interrupt() 挂起等人工批准（HITL 决策层）；
    Java 端 requireSupplierWritePerm 强制 ADMIN 角色 + 幂等 + 参数校验（执行层）。
    回环携带透传的真实用户 JWT → 写操作审计归属发起人，权限在 Java 侧裁决。"""
    name = "create_purchase_order"
    try:
        data = await call_java_tool("/api/agent/purchase-orders", "POST",
                                    json={"supplierId": int(supplier_id), "skuCode": str(sku_code),
                                          "quantity": int(quantity), "unitPrice": float(unit_price)},
                                    timeout_s=TOOL_TIMEOUT_S[name])
    except Exception as e:
        return _err(name, f"Java 服务不可达（连接失败）: {e}")
    env = _as_error_envelope(name, data)
    if env: return env
    if isinstance(data, dict) and data.get("success"):
        return _ok(name, data)
    if isinstance(data, dict) and data.get("duplicate"):
        # 幂等命中：如实回传"未创建"，不冒充新单成功
        return _err(name, str(data.get("msg") or "重复提交，幂等命中"))
    return _err(name, f"创建采购单返回异常: {str(data)[:300]}")


# 显式 tool 元数据，供文档与前端展示
TOOL_DEFS: List[Dict[str, str]] = [
    {"name": "list_low_stock", "desc": "查询所有低于安全库存的SKU"},
    {"name": "get_inventory", "desc": "查询指定SKU库存与安全库存"},
    {"name": "list_suppliers", "desc": "查询供应商列表"},
    {"name": "search_contracts", "desc": "按关键词搜索合同"},
    {"name": "get_contract_risk", "desc": "查询合同风控报告"},
    {"name": "search_catalog", "desc": "搜索商品与SKU"},
    {"name": "search_knowledge", "desc": "知识库语义检索（pgvector 召回+重排），返回带来源的 context"},
    {"name": "create_purchase_order", "desc": "创建采购单（HITL：图内 interrupt 挂起待人工批准，Java 侧 ADMIN+幂等）"},
]
