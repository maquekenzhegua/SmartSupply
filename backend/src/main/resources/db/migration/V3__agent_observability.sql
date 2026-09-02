-- V3: 持久化观测 —— agent_run / agent_step / agent_tool_call / user_feedback
-- 面试亮点：一个 trace_id 回放完整 ReAct 链路（含 token/成本/工具审计）

CREATE TABLE IF NOT EXISTS agent_run (
    id                  BIGSERIAL PRIMARY KEY,
    trace_id            VARCHAR(32)  NOT NULL,
    user_id             BIGINT,
    username            VARCHAR(64),
    session_id          VARCHAR(128),
    agent_type          VARCHAR(32)  NOT NULL DEFAULT 'general',
    mode                VARCHAR(16)  NOT NULL DEFAULT 'java-direct',
    status              VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    latency_ms          INTEGER,
    ttfb_ms             INTEGER,
    prompt_tokens       INTEGER      NOT NULL DEFAULT 0,
    completion_tokens   INTEGER      NOT NULL DEFAULT 0,
    total_tokens        INTEGER      NOT NULL DEFAULT 0,
    cost_usd            NUMERIC(10,6) NOT NULL DEFAULT 0,
    token_source        VARCHAR(16)  NOT NULL DEFAULT 'estimated',
    model               VARCHAR(64),
    error_msg           TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_run_trace ON agent_run(trace_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_user_time ON agent_run(username, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run(session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_created ON agent_run(created_at DESC);

CREATE TABLE IF NOT EXISTS agent_step (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    seq             INTEGER      NOT NULL,
    node            VARCHAR(32)  NOT NULL,
    name            VARCHAR(128),
    input_digest    VARCHAR(64),
    output_digest   VARCHAR(64),
    latency_ms      INTEGER,
    success         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_step_run ON agent_step(run_id, seq);

CREATE TABLE IF NOT EXISTS agent_tool_call (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_id         BIGINT       REFERENCES agent_step(id) ON DELETE SET NULL,
    tool            VARCHAR(64)  NOT NULL,
    args_json       JSONB,
    result_digest   VARCHAR(64),
    success         BOOLEAN      NOT NULL DEFAULT true,
    latency_ms      INTEGER,
    user_role       VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_tool_run ON agent_tool_call(run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_tool_name ON agent_tool_call(tool, created_at DESC);

CREATE TABLE IF NOT EXISTS user_feedback (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT       REFERENCES chat_message(id) ON DELETE SET NULL,
    run_id          BIGINT       REFERENCES agent_run(id) ON DELETE SET NULL,
    session_id      VARCHAR(128),
    rating          SMALLINT     NOT NULL,
    comment         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_feedback_run ON user_feedback(run_id);
CREATE INDEX IF NOT EXISTS idx_feedback_rating ON user_feedback(rating, created_at DESC);

-- prompt 版本治理（阶段 4）：激活版 + 回滚
CREATE TABLE IF NOT EXISTS prompt_version (
    id              BIGSERIAL PRIMARY KEY,
    agent_type      VARCHAR(32)  NOT NULL,
    version         VARCHAR(32)  NOT NULL,
    content         TEXT         NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT false,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE(agent_type, version)
);
CREATE INDEX IF NOT EXISTS idx_prompt_active ON prompt_version(agent_type, active);
