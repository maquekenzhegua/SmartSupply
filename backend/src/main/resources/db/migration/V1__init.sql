-- SmartSupply init.sql — PostgreSQL + pgvector
-- 兼容：若无 pgvector 扩展，向量表退化为普通表（不建 embedding 列，RAG 用内存检索 Demo）

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
-- 扩展缺失时不可让迁移失败：捕获并降级（本机开发环境可能未装 pgvector）
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector 扩展不可用，knowledge_chunk 将按普通表降级创建';
END $$;

-- ========== 基础 ==========
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    nickname        VARCHAR(64),
    role            VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS supplier (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    contact_name    VARCHAR(64),
    contact_phone   VARCHAR(32),
    email           VARCHAR(128),
    address         VARCHAR(255),
    rating          NUMERIC(3,2) DEFAULT 4.50,
    status          VARCHAR(16)  DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS product (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    category        VARCHAR(64),
    unit            VARCHAR(16)  DEFAULT '件',
    bar_code        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sku (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT       NOT NULL REFERENCES product(id),
    sku_code        VARCHAR(64)  NOT NULL UNIQUE,
    spec            VARCHAR(128),
    cost_price      NUMERIC(12,2) NOT NULL DEFAULT 0,
    sale_price      NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS warehouse (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64) NOT NULL,
    location        VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS inventory (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT  NOT NULL REFERENCES sku(id),
    warehouse_id    BIGINT  NOT NULL REFERENCES warehouse(id),
    quantity        INTEGER NOT NULL DEFAULT 0,
    safety_stock    INTEGER NOT NULL DEFAULT 100,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(sku_id, warehouse_id)
);

CREATE TABLE IF NOT EXISTS inventory_flow (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT  NOT NULL REFERENCES sku(id),
    warehouse_id    BIGINT  NOT NULL REFERENCES warehouse(id),
    change_qty      INTEGER NOT NULL,
    reason          VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id              BIGSERIAL PRIMARY KEY,
    order_no        VARCHAR(32)  NOT NULL UNIQUE,
    supplier_id     BIGINT       REFERENCES supplier(id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    total_amount    NUMERIC(12,2) DEFAULT 0,
    remark          VARCHAR(512),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS purchase_order_item (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    sku_id          BIGINT       NOT NULL REFERENCES sku(id),
    quantity        INTEGER      NOT NULL,
    unit_price      NUMERIC(12,2) NOT NULL,
    amount          NUMERIC(12,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS contract (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    supplier_id     BIGINT       REFERENCES supplier(id),
    file_url        VARCHAR(512),
    file_name       VARCHAR(255),
    status          VARCHAR(16)  DEFAULT 'DRAFT',
    amount          NUMERIC(12,2),
    sign_date       DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS contract_risk_report (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     BIGINT       NOT NULL REFERENCES contract(id) ON DELETE CASCADE,
    risk_level      VARCHAR(16)  NOT NULL,
    summary         TEXT,
    risks_json      JSONB,
    suggestion      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- RAG 知识库
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    source_type     VARCHAR(32)  NOT NULL DEFAULT 'CONTRACT',
    file_url        VARCHAR(512),
    content         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- pgvector 向量表（默认 1024 维适配本地 Ollama qwen3-embedding:0.6b / bge-m3；换 OpenAI text-embedding-3-small 时改 1536）
-- 有 pgvector 时带 embedding 列与 ivfflat 索引；无扩展时降级为普通表（embedding 列由 Spring AI 的 vector_store 承担）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        CREATE TABLE IF NOT EXISTS knowledge_chunk (
            id              BIGSERIAL PRIMARY KEY,
            doc_id          BIGINT       REFERENCES knowledge_doc(id) ON DELETE CASCADE,
            chunk_index     INTEGER      NOT NULL DEFAULT 0,
            content         TEXT         NOT NULL,
            embedding       vector(1024),
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
    ELSE
        CREATE TABLE IF NOT EXISTS knowledge_chunk (
            id              BIGSERIAL PRIMARY KEY,
            doc_id          BIGINT       REFERENCES knowledge_doc(id) ON DELETE CASCADE,
            chunk_index     INTEGER      NOT NULL DEFAULT 0,
            content         TEXT         NOT NULL,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS chat_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,
    agent_type      VARCHAR(32)  NOT NULL,
    title           VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT       NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL,
    tool_calls_json JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ========== 演示数据 ==========
INSERT INTO sys_user (username, password_hash, nickname, role) VALUES
('admin', '$2a$10$DUMMY_BCRYPT_HASH_REPLACE_ON_STARTUP', '管理员', 'ADMIN')
ON CONFLICT (username) DO NOTHING;

INSERT INTO supplier (name, contact_name, contact_phone, email, rating) VALUES
('深圳创优服装厂', '王厂长', '13800001111', 'wang@cy-factory.cn', 4.80),
('东莞精工纺织', '李经理', '13900002222', 'li@jg-textile.cn', 4.60),
('广州尚品供应链', '陈总', '13700003333', 'chen@sp-supply.cn', 4.30)
ON CONFLICT DO NOTHING;

INSERT INTO warehouse (name, location) VALUES
('华南中心仓', '深圳宝安'),
('华东中心仓', '上海嘉定')
ON CONFLICT DO NOTHING;

INSERT INTO product (name, category, unit) VALUES
('定制纯棉T恤', '服装', '件'),
('帆布托特包', '箱包', '个'),
('不锈钢保温杯', '日用', '个')
ON CONFLICT DO NOTHING;

INSERT INTO sku (product_id, sku_code, spec, cost_price, sale_price) VALUES
(1, 'SKU-T001-WH-M', '白色/M', 28.00, 59.00),
(1, 'SKU-T001-BK-L', '黑色/L', 28.00, 59.00),
(2, 'SKU-B001-BE', '米色/均码', 18.00, 39.00),
(3, 'SKU-C001-SV-500', '银色/500ml', 35.00, 79.00)
ON CONFLICT (sku_code) DO NOTHING;

INSERT INTO inventory (sku_id, warehouse_id, quantity, safety_stock) VALUES
(1, 1, 120, 200),
(2, 1, 800, 150),
(3, 2, 45, 100),
(4, 1, 300, 100)
ON CONFLICT (sku_id, warehouse_id) DO NOTHING;

INSERT INTO contract (title, supplier_id, status, amount) VALUES
('2026年度T恤采购框架合同', 1, 'REVIEWING', 280000.00),
('帆布包季度供货协议', 2, 'ACTIVE', 90000.00)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_doc (title, source_type, content) VALUES
('公司合同风控规范（节选）', 'POLICY', '1. 禁止无限连带责任条款；2. 违约金不得超过合同额30%；3. 交付时间必须明确到日，模糊表述视为风险；4. 争议解决地应为我方所在地。'),
('历史风险案例：无限责任条款', 'CASE', '案例：2024年与XX厂合同因“乙方承担一切连带责任”导致纠纷，教训：必须改为“在乙方过错范围内承担有限责任”。')
ON CONFLICT DO NOTHING;
