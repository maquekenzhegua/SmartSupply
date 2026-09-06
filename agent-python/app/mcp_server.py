"""MCP (Model Context Protocol) 服务器：把边车 8 工具按开放协议暴露给任意 MCP 客户端。

价值：工具从"边车私有 registry"升级为"标准协议可发现可调用"——Claude Desktop、
IDE Agent、任何 MCP 客户端无需了解 SmartSupply 内部即可复用同一套供应链工具，
实现复用而非每个系统重复造工具层。

- 传输：stdio（标准本地接法，如 Claude Desktop 的 mcpServers 配置）——
  cd agent-python && python -m app.mcp_server
- 读工具（7 个）直接转发 app.tools：回环鉴权与直调边车相同，走
  JAVA_JWT_TOKEN / SIDECAR 账号自动登录兜底（身份与审计语义不变）。
- 写工具（create_purchase_order）**默认不暴露**：MCP 传输通道里没有 LangGraph
  图内 interrupt 的人工批准环节，把写工具挂上去等于绕过 HITL 闸门。确需开启
  （如运维 Copilot 场景）设 MCP_ENABLE_WRITE=1——即使开启，Java 执行层的
  ADMIN 鉴权/幂等/DRAFT 审批仍然生效，纵深防御不减一层。
"""
import os
from typing import Any, Dict

from mcp.server.fastmcp import FastMCP

from . import tools

mcp = FastMCP("smartsupply-supplychain")


def _ok(result: Dict[str, Any]) -> Dict[str, Any]:
    """工具信封原样返回（ok=False 的失败语义在 MCP 通道同样保留，不伪装成功）。"""
    return result


@mcp.tool()
async def list_low_stock() -> Dict[str, Any]:
    """列出低于安全库存的 SKU（补货候选）"""
    return _ok(await tools.tool_list_low_stock())


@mcp.tool()
async def get_inventory(sku_code: str) -> Dict[str, Any]:
    """查询指定 SKU 编码的库存分布（分仓数量与安全库存状态）"""
    return _ok(await tools.tool_get_inventory(sku_code))


@mcp.tool()
async def list_suppliers() -> Dict[str, Any]:
    """列出供应商及其评级、合作状态"""
    return _ok(await tools.tool_list_suppliers())


@mcp.tool()
async def search_contracts(keyword: str) -> Dict[str, Any]:
    """按关键词搜索采购/销售合同摘要与风险等级"""
    return _ok(await tools.tool_search_contracts(keyword))


@mcp.tool()
async def get_contract_risk(contract_id: int) -> Dict[str, Any]:
    """查询指定合同的治理风险项（责任条款/违约金/无限连带等）"""
    return _ok(await tools.tool_get_contract_risk(contract_id))


@mcp.tool()
async def search_knowledge(keyword: str) -> Dict[str, Any]:
    """供应链知识库语义检索（制度/合规/系统参数，pgvector + 重排）"""
    return _ok(await tools.tool_search_knowledge(keyword))


@mcp.tool()
async def search_catalog(keyword: str) -> Dict[str, Any]:
    """按关键词检索商品目录/SKU 编码"""
    return _ok(await tools.tool_search_catalog(keyword))


if os.getenv("MCP_ENABLE_WRITE", "") == "1":
    @mcp.tool()
    async def create_purchase_order(supplier_id: int, sku_code: str, quantity: int, unit_price: float) -> Dict[str, Any]:
        """创建 DRAFT 采购单（写操作）。MCP 通道无人工批准环节，须 MCP_ENABLE_WRITE=1 显式开启；
        Java 侧仍强制 ADMIN 角色 + 幂等 + DRAFT 人工审批"""
        return _ok(await tools.tool_create_purchase_order(supplier_id, sku_code, quantity, unit_price))


def main() -> None:
    # stdio 传输：stdout 归 MCP 协议，任何 print 都会破坏帧——工具内的日志走 stderr
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
