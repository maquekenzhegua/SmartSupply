"""边车工具回环回归 —— 直接测 app.tools 的真实代码：
   - token 优先级：透传用户 JWT > 服务账号 JAVA_JWT_TOKEN
   - 失败传播：403/401、HTTP 错误、连接异常一律返回 ok=False 信封并如实带原因，
     绝不吞成空列表、绝不返回带 demo:True 的伪造业务数据（评测/演示拿假数据当真结果是
     此前最大的信任缺陷）。
   离线可跑：全程 monkeypatch call_java_tool，无需真实 Java/网络。
"""
import asyncio

import pytest

from app import tools as T


@pytest.fixture(autouse=True)
def _reset_contextvars():
    """每个用例隔离 contextvar，避免用例间 token 串号。"""
    tok = T._token_var.set("")
    tr = T._trace_var.set("")
    yield
    T._token_var.reset(tok)
    T._trace_var.reset(tr)


def test_headers_prefers_delegated_token(monkeypatch):
    monkeypatch.setattr(T.config, "JAVA_JWT_TOKEN", "svc-token")
    T._token_var.set("user-jwt")
    h = T._headers()
    assert h["Authorization"] == "Bearer user-jwt"  # 透传用户优先


def test_headers_falls_back_to_service_token(monkeypatch):
    monkeypatch.setattr(T.config, "JAVA_JWT_TOKEN", "svc-token")
    h = T._headers()
    assert h["Authorization"] == "Bearer svc-token"


def test_headers_no_token_no_auth_header(monkeypatch):
    monkeypatch.setattr(T.config, "JAVA_JWT_TOKEN", "")
    h = T._headers()
    assert "Authorization" not in h


_AUTH_ERR = {"error": "Java API 403: forbidden", "auth_failed": True}
_HTTP_ERR = {"error": "Java API 500: internal", "auth_failed": False}


def _patch(monkeypatch, ret=None, raises=None):
    async def fake(*a, **k):
        if raises:
            raise raises
        return ret
    monkeypatch.setattr(T, "call_java_tool", fake)


@pytest.mark.parametrize("fn,args", [
    (T.tool_list_low_stock, ()),
    (T.tool_list_suppliers, ()),
    (T.tool_search_contracts, ("服装",)),
    (T.tool_search_catalog, ("T恤",)),
    (T.tool_search_knowledge, ("风控规范",)),
    (T.tool_get_inventory, ("SKU-T001-WH-M",)),
    (T.tool_get_contract_risk, (1,)),
])
def test_tool_propagates_auth_error(monkeypatch, fn, args):
    _patch(monkeypatch, ret=_AUTH_ERR)
    out = asyncio.run(fn(*args))
    assert out["ok"] is False
    assert out["auth_failed"] is True
    assert "403" in out["error"]
    assert not out.get("demo")  # 绝不带伪造数据


@pytest.mark.parametrize("fn,args", [
    (T.tool_list_low_stock, ()),
    (T.tool_list_suppliers, ()),
    (T.tool_search_contracts, ("服装",)),
    (T.tool_search_catalog, ("T恤",)),
    (T.tool_search_knowledge, ("风控规范",)),
    (T.tool_get_inventory, ("SKU-T001-WH-M",)),
    (T.tool_get_contract_risk, (1,)),
])
def test_tool_propagates_http_error(monkeypatch, fn, args):
    _patch(monkeypatch, ret=_HTTP_ERR)
    out = asyncio.run(fn(*args))
    assert out["ok"] is False and out["auth_failed"] is False
    assert "500" in out["error"]


@pytest.mark.parametrize("fn,args", [
    (T.tool_list_low_stock, ()),
    (T.tool_list_suppliers, ()),
    (T.tool_search_contracts, ("服装",)),
    (T.tool_search_catalog, ("T恤",)),
    (T.tool_search_knowledge, ("风控规范",)),
    (T.tool_get_inventory, ("SKU-T001-WH-M",)),
    (T.tool_get_contract_risk, (1,)),
])
def test_tool_connection_error_is_honest_not_demo(monkeypatch, fn, args):
    """Java 未启动（连接异常）如实报 unavailable，demo 降级已整体移除。"""
    _patch(monkeypatch, raises=RuntimeError("connection refused"))
    out = asyncio.run(fn(*args))
    assert out["ok"] is False
    assert "connection refused" in out["error"]
    assert not out.get("demo")


def test_search_knowledge_success_envelope(monkeypatch):
    _patch(monkeypatch, ret={"context": "禁止无限连带责任", "citations": [{"docId": 1, "title": "风控规范"}],
                             "vectorHits": 3, "reranked": 2})
    out = asyncio.run(T.tool_search_knowledge("无限连带责任"))
    assert out["ok"] is True
    assert "禁止无限连带责任" in out["data"]["context"]
    assert out["data"]["citations"][0]["title"] == "风控规范"


def test_tool_success_envelope_carries_data(monkeypatch):
    _patch(monkeypatch, ret=[{"sku_code": "SKU-T001-WH-M", "quantity": 120, "safety_stock": 200}])
    out = asyncio.run(T.tool_list_low_stock())
    assert out["ok"] is True
    assert out["data"][0]["sku_code"] == "SKU-T001-WH-M"


def test_tool_pagination_envelope_unwrapped(monkeypatch):
    _patch(monkeypatch, ret={"rows": [{"id": 1, "name": "某供应商"}], "total": 1})
    out = asyncio.run(T.tool_list_suppliers())
    assert out["ok"] is True and out["data"][0]["name"] == "某供应商"


def test_no_demo_literals_left_in_tools():
    """结构性约束：tools.py 源码中不得再出现 demo 数据字面量。"""
    import inspect
    src = inspect.getsource(T)
    assert '"demo": True' not in src and "'demo': True" not in src
    assert "降级演示" not in src and "深圳创优服装厂" not in src
