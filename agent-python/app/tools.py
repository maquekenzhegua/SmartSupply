"""Python 侧对 Java Tool API 的回调封装 + LangGraph 工具调度。Java 是可信执行层，Python 只做决策。"""
import httpx
from typing import Any, Dict, List
from . import config


def _headers() -> Dict[str, str]:
    h: Dict[str, str] = {}
    if config.JAVA_JWT_TOKEN:
        h["Authorization"] = f"Bearer {config.JAVA_JWT_TOKEN}"
    return h


async def call_java_tool(path: str, method: str = "GET", params: Dict[str, Any] = None, json: Dict[str, Any] = None) -> Any:
    url = f"{config.JAVA_API_BASE.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=10) as client:
        if method == "GET":
            r = await client.get(url, params=params, headers=_headers())
        else:
            r = await client.request(method, url, params=params, json=json, headers=_headers())
        if r.status_code != 200:
            return {"error": f"Java API {r.status_code}: {r.text[:500]}", "fallback": True}
        try:
            data = r.json()
        except Exception:
            return {"raw": r.text[:1000]}
        if isinstance(data, dict) and "data" in data:
            return data["data"]
        return data


async def tool_list_low_stock() -> List[Dict[str, Any]]:
    try:
        data = await call_java_tool("/api/inventory/low-stock", "GET")
    except Exception as e:
        return [{"note": f"Java 未启动，降级演示: {e}", "demo": True, "sku_code": "SKU-T001-WH-M", "quantity": 120, "safety_stock": 200}]
    if isinstance(data, dict) and "error" in data:
        return []
    if isinstance(data, list):
        return data
    return []


async def tool_get_inventory(sku_code: str) -> Dict[str, Any]:
    try:
        data = await call_java_tool("/api/inventory/by-sku", "GET", params={"skuCode": sku_code})
        if isinstance(data, dict) and "error" in data:
            raise RuntimeError(str(data))
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    rows: List[Dict[str, Any]] = await tool_list_low_stock()
    for r in rows:
        if r.get("sku_code") == sku_code:
            return {"found": True, "data": r}
    return {"found": False, "msg": f"未在低库存列表中找到 {sku_code}，建议用库存管理页查询"}


async def tool_list_suppliers() -> List[Dict[str, Any]]:
    try:
        data = await call_java_tool("/api/suppliers", "GET")
        if isinstance(data, dict) and "rows" in data:
            return data["rows"] if isinstance(data["rows"], list) else []
        if isinstance(data, dict) and "records" in data:
            return data["records"] if isinstance(data["records"], list) else []
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and "error" in data:
            return []
    except Exception as e:
        return [{"note": f"供应商查询降级: {e}", "demo": True, "id": 1, "name": "深圳创优服装厂", "rating": 4.8}]
    return []


async def tool_search_contracts(keyword: str) -> List[Dict[str, Any]]:
    try:
        data = await call_java_tool("/api/contracts", "GET", params={"keyword": keyword or ""})
        if isinstance(data, list):
            kw = (keyword or "").lower()
            if kw:
                return [x for x in data if kw in str(x.get("title", "")).lower()][:10]
            return data[:10]
        if isinstance(data, dict) and "rows" in data and isinstance(data["rows"], list):
            return data["rows"][:10]
        if isinstance(data, dict) and "error" in data:
            return []
    except Exception:
        pass
    # 降级：通过 demo 合同
    return [{"id": 1, "title": "2026年度T恤采购框架合同", "status": "REVIEWING", "amount": 280000, "demo": True}]


async def tool_get_contract_risk(contract_id: int) -> Dict[str, Any]:
    try:
        data = await call_java_tool(f"/api/contracts/{contract_id}/risk-report", "GET")
        if isinstance(data, dict) and "error" not in data:
            return data
    except Exception as e:
        return {"found": False, "error": str(e), "demo": True}
    if isinstance(data, dict) and "error" in data:
        return {"found": False, "msg": data.get("error")}
    return {"found": True, "report": data}


async def tool_search_catalog(keyword: str) -> List[Dict[str, Any]]:
    try:
        data = await call_java_tool("/api/products/catalog/search", "GET", params={"keyword": keyword or ""})
        if isinstance(data, list):
            return data[:10]
        if isinstance(data, dict) and "rows" in data and isinstance(data["rows"], list):
            return data["rows"][:10]
        if isinstance(data, dict) and "error" in data:
            return []
    except Exception:
        pass
    return [{"product_name": "定制纯棉T恤", "sku_code": "SKU-T001-WH-M", "spec": "白色/M", "demo": True}]


# 显式 tool 元数据，供文档与前端展示
TOOL_DEFS: List[Dict[str, str]] = [
    {"name": "list_low_stock", "desc": "查询所有低于安全库存的SKU"},
    {"name": "get_inventory", "desc": "查询指定SKU库存与安全库存"},
    {"name": "list_suppliers", "desc": "查询供应商列表"},
    {"name": "search_contracts", "desc": "按关键词搜索合同"},
    {"name": "get_contract_risk", "desc": "查询合同风控报告"},
    {"name": "search_catalog", "desc": "搜索商品与SKU"},
]
