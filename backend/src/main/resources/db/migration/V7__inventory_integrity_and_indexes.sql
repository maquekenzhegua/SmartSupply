-- V7: 库存完整性约束 + 高频查询索引
-- 背景：库存调整此前是"读-改-写"（绝对值覆盖），并发下互相覆盖且可产生负库存；
-- 应用层已改为原子增量 UPDATE（WHERE quantity + ? >= 0），此处补数据库层兜底与索引。

-- 先归一历史负库存（旧实现竞态的遗留脏数据），否则 CHECK 约束加不上
UPDATE inventory SET quantity = 0 WHERE quantity < 0;

ALTER TABLE inventory ADD CONSTRAINT chk_inventory_qty_nonneg CHECK (quantity >= 0);

-- PG 不会自动为 FK 建索引；会话记忆按 session_id 查（chat_message），采购入库/详情按 order_id 查
-- （purchase_order_item），聊天量与采购量增长后无索引即全表扫描
CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message(session_id);
CREATE INDEX IF NOT EXISTS idx_po_item_order ON purchase_order_item(order_id);
