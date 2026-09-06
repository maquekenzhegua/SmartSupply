-- V4: 采购单审批留痕。DRAFT->APPROVED 时记录审批人与审批时间（API 层强制 ADMIN，
-- 见 PurchaseExtraController.updateStatus 的状态机与 RBAC 校验）。
ALTER TABLE purchase_order ADD COLUMN IF NOT EXISTS approver VARCHAR(64);
ALTER TABLE purchase_order ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;
