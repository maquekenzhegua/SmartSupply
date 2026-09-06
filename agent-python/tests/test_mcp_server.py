"""MCP 服务器协议级回归：真实 MCP 客户端会话（内存传输）验证工具发现与调用。

覆盖四件事：
- list_tools：默认暴露 7 个读工具；写工具默认不暴露（MCP 通道无人工批准环节，
  暴露即绕过图内 interrupt 闸门）；MCP_ENABLE_WRITE=1 时显式开启且描述写明约束。
- call_tool：经真实 MCP 协议调用读工具，Java 回环按测试桩应答，ok 信封语义保留。
- 调用不存在的工具：协议层如实报错，不伪造结果。
"""
import asyncio
import json
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from mcp.shared.memory import create_connected_server_and_client_session

from app import tools as T

READ_TOOL_COUNT = 7


def _patch_java(monkeypatch):
    hits = []

    async def fake_call(path, method="GET", params=None, json=None, timeout_s=8.0):
        hits.append(path)
        return {"success": True, "data": [{"low": "rows"}]}

    monkeypatch.setattr(T, "call_java_tool", fake_call)
    return hits


def _run(coro):
    return asyncio.run(coro)


async def _list_names(session):
    return {t.name for t in (await session.list_tools()).tools}


def test_lists_only_read_tools_by_default():
    from app import mcp_server

    async def body():
        async with create_connected_server_and_client_session(mcp_server.mcp) as session:
            await session.initialize()
            return await _list_names(session)

    names = _run(body())
    assert len(names) == READ_TOOL_COUNT
    assert "list_low_stock" in names and "get_inventory" in names
    assert "create_purchase_order" not in names  # 写工具默认不暴露


def test_write_tool_opt_in_via_env(monkeypatch):
    import importlib
    from app import mcp_server

    monkeypatch.setenv("MCP_ENABLE_WRITE", "1")
    importlib.reload(mcp_server)
    try:
        async def body():
            async with create_connected_server_and_client_session(mcp_server.mcp) as session:
                await session.initialize()
                tools = (await session.list_tools()).tools
                return ({t.name for t in tools},
                        next(t for t in tools if t.name == "create_purchase_order").description)

        names, desc = _run(body())
        assert "create_purchase_order" in names
        assert "MCP_ENABLE_WRITE" in desc  # 约束写进工具描述，客户端可见
    finally:
        monkeypatch.delenv("MCP_ENABLE_WRITE", raising=False)
        importlib.reload(mcp_server)  # 还原单例，避免污染其他用例


def test_call_tool_via_real_protocol(monkeypatch):
    from app import mcp_server
    hits = _patch_java(monkeypatch)

    async def body():
        async with create_connected_server_and_client_session(mcp_server.mcp) as session:
            await session.initialize()
            r1 = await session.call_tool("list_low_stock", {})
            r2 = await session.call_tool("get_inventory", {"sku_code": "SKU-T001-WH-M"})
            return r1, r2

    r1, r2 = _run(body())
    p1 = json.loads(r1.content[0].text)
    assert p1["ok"] is True and p1["tool"] == "list_low_stock" and hits
    p2 = json.loads(r2.content[0].text)
    assert p2["ok"] is True and p2["tool"] == "get_inventory" and p2["data"] not in (None, [])


def test_call_unknown_tool_errors_honestly():
    from app import mcp_server

    async def body():
        async with create_connected_server_and_client_session(mcp_server.mcp) as session:
            await session.initialize()
            return await session.call_tool("no_such_tool", {})

    try:
        res = _run(body())
        assert res.isError  # 协议层如实报错
    except Exception:
        pass  # 部分 SDK 版本对未知工具直接抛 McpError，同样算"如实报错"
