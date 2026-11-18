-- ============================================================================
-- AgenticUPI Schema (PostgreSQL 16 + pgvector)
-- Production-grade Financial & Agentic AI Infrastructure
-- ============================================================================

-- Enable pgvector extension for AI embeddings
CREATE EXTENSION IF NOT EXISTS vector;

-- Custom ENUM Types
CREATE TYPE user_status AS ENUM ('ACTIVE', 'SUSPENDED');
CREATE TYPE agent_status AS ENUM ('ACTIVE', 'REVOKED');
CREATE TYPE mandate_status AS ENUM ('ACTIVE', 'PAUSED', 'REVOKED', 'EXPIRED');
CREATE TYPE txn_status AS ENUM ('LEDGER_RESERVED', 'SETTLED', 'FAILED');
CREATE TYPE entry_direction AS ENUM ('CREDIT', 'DEBIT');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'IN_PROGRESS', 'PUBLISHED', 'FAILED');

-- 1. Users Table
CREATE TABLE users (
    vpa VARCHAR(100) PRIMARY KEY,
    status user_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Account Balances with Optimistic Locking
CREATE TABLE account_balances (
    user_vpa VARCHAR(100) PRIMARY KEY REFERENCES users(vpa),
    balance_paise BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1  -- Optimistic Concurrency Control (OCC)
);

-- 3. Agent Credentials (Shared Secret + KMS Encryption)
CREATE TABLE agent_credentials (
    agent_id VARCHAR(50) PRIMARY KEY,
    agent_name VARCHAR(100) NOT NULL,
    secret_ciphertext VARCHAR(255) NOT NULL,
    secret_kms_key_id VARCHAR(100) NOT NULL,
    status agent_status NOT NULL DEFAULT 'ACTIVE'
);

-- 4. Mandates with Native Partial Unique Index
CREATE TABLE mandates (
    umn VARCHAR(100) PRIMARY KEY, -- Unique Mandate Number
    agent_id VARCHAR(50) NOT NULL REFERENCES agent_credentials(agent_id),
    user_vpa VARCHAR(100) NOT NULL REFERENCES users(vpa),
    daily_limit_paise BIGINT NOT NULL,
    status mandate_status NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Production Partial Unique Index:
-- Guarantees at most ONE ACTIVE or PAUSED mandate per (agent_id, user_vpa) pair,
-- while allowing unlimited historical REVOKED/EXPIRED records without table bloat!
CREATE UNIQUE INDEX idx_unique_active_mandate 
ON mandates (agent_id, user_vpa) 
WHERE status IN ('ACTIVE', 'PAUSED');

-- 5. Transactions with Idempotency Guard & AI Embedding Support
CREATE TABLE transactions (
    txn_id VARCHAR(100) PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    agent_id VARCHAR(50) NOT NULL REFERENCES agent_credentials(agent_id),
    payer_vpa VARCHAR(100) NOT NULL,
    payee_vpa VARCHAR(100) NOT NULL,
    amount_paise BIGINT NOT NULL,
    status txn_status NOT NULL,
    purpose_description TEXT,
    embedding vector(384), -- AI semantic embedding (e.g. all-MiniLM-L6-v2) for fraud / category clustering
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Hard DB Idempotency Constraint
    CONSTRAINT uq_idempotency_agent UNIQUE (idempotency_key, agent_id)
);

-- 6. Immutable Double-Entry Ledger
CREATE TABLE ledger_entries (
    entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txn_id VARCHAR(100) NOT NULL REFERENCES transactions(txn_id),
    account_id VARCHAR(100) NOT NULL,
    direction entry_direction NOT NULL,
    amount_paise BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 7. Transactional Outbox Pattern with JSONB
CREATE TABLE outbox_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL, -- Binary JSON for high-performance indexing and queries
    status outbox_status NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(100) NULL, -- Worker ID for SELECT ... FOR UPDATE SKIP LOCKED
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- High-throughput index for Outbox polling workers
CREATE INDEX idx_outbox_polling ON outbox_events (status, created_at);

-- ============================================================================
-- Seed Data for Testing & Demonstration
-- ============================================================================
INSERT INTO users (vpa, status) VALUES ('user@upi', 'ACTIVE');
INSERT INTO account_balances (user_vpa, balance_paise) VALUES ('user@upi', 500000); -- 5000 INR

INSERT INTO agent_credentials (agent_id, agent_name, secret_ciphertext, secret_kms_key_id, status)
VALUES ('agent_demo_1', 'Demo AI Travel Agent', 'my_raw_db_secret', 'mock_kms', 'ACTIVE');

INSERT INTO mandates (umn, agent_id, user_vpa, daily_limit_paise, status)
VALUES ('umn_demo_123', 'agent_demo_1', 'user@upi', 100000, 'ACTIVE');
