# Agent 工具目录（Tool Catalog）

Agent 可调用以下 7 个工具，全部经过 ToolSecurity 鉴权并接入观测埋点：

- getInventory：查询 SKU 库存与安全库存，参数为 skuCode。
- listLowStock：查询所有低于安全库存的 SKU，无需参数。
- listSuppliers：查询供应商列表，无需参数；仅返回供应商名称与评分，不包含联系方式等敏感字段。
- searchCatalog：搜索商品与 SKU，参数为 keyword。
- searchContracts：按关键词搜索合同，参数为 keyword。
- getContractRisk：查询合同风控报告，参数为 contractId。
- createPurchaseOrder：创建采购单，需指定供应商ID（supplierId）、SKU编码（skuCode）、数量（quantity）和单价（unitPrice），返回采购单号。
