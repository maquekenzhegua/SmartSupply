# 采购流程与人工确认（HITL）

采购流程：采购单默认 DRAFT，需人工确认才转 APPROVED，防止 Agent 误下单；采购单创建后不能直接审批，必须经人工审核。

写操作 HITL 规则：
- 创建采购单需二次确认；写意图未确认时拒绝创建，未确认的写意图返回 needConfirm=true。
- confirmCreate=true 时放行写操作，否则返回 needConfirm。
- 二次确认文案：该操作将创建采购单（DRAFT，需人工审批）。请回复"确认创建"。
- HITL 写意图需二次确认，不受 prompt 注入影响。

幂等：createPurchaseOrder 按 idem:po:{user}:{supplier}:{sku}:{qty}:{price} 做 10 分钟去重，重复提交相同采购单会被幂等拦截，直接返回已存在的采购单号。

权限：ToolSecurity 规定 createPurchaseOrder 需要 ADMIN 角色；查询类工具所有登录用户可用。
