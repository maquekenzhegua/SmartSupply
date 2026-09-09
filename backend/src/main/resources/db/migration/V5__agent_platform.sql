-- V5: Agent 平台化补强 —— Prompt 中心闭环 / 成本台账口径 / 在线评测闭环 / 记忆持久化
-- 设计动机：prompt_version 表此前只写不读（激活不生效）、agent_run 无提示词版本归因、
-- 会话靠 title 反查脆弱、滚动摘要只存 Redis 重启即丢、离线评测指标无时序留痕。

-- 1) run 台账归因：本次对话生效的系统提示词版本（java-direct=PromptRegistry 生效版；深度模式=persona 版）
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32);

-- 2) 会话稳定键（title 反查升级为 session_key 等值查询，唯一索引兜底幂等）+ 摘要持久化（Redis 失可从 DB 恢复）
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS session_key VARCHAR(128);
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS summary TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_session_key ON chat_session(session_key);

-- 3) 评测快照：llm_judge / eval_trajectory 的指标随时间留痕，看板出趋势（在线反馈闭环的时序半边）
-- metrics 用 TEXT 存 JSON 串（与 agent_tool_call.args_json 的 H2/PG 双端口径一致，避免 jsonb 方言分裂）
CREATE TABLE IF NOT EXISTS eval_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    source          VARCHAR(32)  NOT NULL,
    report_file     VARCHAR(255),
    metrics         TEXT,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_eval_snapshot_time ON eval_snapshot(created_at DESC);
