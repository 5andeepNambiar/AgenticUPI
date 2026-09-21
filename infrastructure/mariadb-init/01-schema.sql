-- AgenticUPI Schema Definition (Based exactly on Phase 2 Docs)

CREATE TABLE users (
    vpa VARCHAR(100) PRIMARY KEY,
    status ENUM('ACTIVE', 'SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE account_balances (
    user_vpa VARCHAR(100) PRIMARY KEY,
    balance_paise BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,  -- Optimistic Locking column
    FOREIGN KEY (user_vpa) REFERENCES users(vpa)
);

CREATE TABLE agent_credentials (
    agent_id VARCHAR(50) PRIMARY KEY,
    agent_name VARCHAR(100) NOT NULL,
    secret_ciphertext VARCHAR(255) NOT NULL, -- KMS encrypted shared secret
    secret_kms_key_id VARCHAR(100) NOT NULL,
    status ENUM('ACTIVE', 'REVOKED') NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE mandates (
    umn VARCHAR(100) PRIMARY KEY, -- Unique Mandate Number
    agent_id VARCHAR(50) NOT NULL,
    user_vpa VARCHAR(100) NOT NULL,
    daily_limit_paise BIGINT NOT NULL,
    status ENUM('ACTIVE', 'PAUSED', 'REVOKED', 'EXPIRED') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- The Interview Signal: Generated Column for Partial Unique Index bounds
    -- Prevents multiple ACTIVE/PAUSED mandates for the same Agent+VPA, but allows
    -- infinite REVOKED/EXPIRED historical records because NULLs are ignored in UNIQUE constraints.
    active_key VARCHAR(150) AS (
        IF(status IN ('ACTIVE','PAUSED'), CONCAT(agent_id, '_', user_vpa), NULL)
    ) VIRTUAL,

    UNIQUE KEY idx_unique_active_mandate (active_key),
    FOREIGN KEY (agent_id) REFERENCES agent_credentials(agent_id),
    FOREIGN KEY (user_vpa) REFERENCES users(vpa)
);

CREATE TABLE transactions (
    txn_id VARCHAR(100) PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    agent_id VARCHAR(50) NOT NULL,
    payer_vpa VARCHAR(100) NOT NULL,
    payee_vpa VARCHAR(100) NOT NULL,
    amount_paise BIGINT NOT NULL,
    status ENUM('LEDGER_RESERVED', 'SETTLED', 'FAILED') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY idx_idempotency (idempotency_key, agent_id), -- Hard DB idempotency guard
    FOREIGN KEY (agent_id) REFERENCES agent_credentials(agent_id)
);

CREATE TABLE ledger_entries (
    entry_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    txn_id VARCHAR(100) NOT NULL,
    account_id VARCHAR(100) NOT NULL,
    direction ENUM('CREDIT', 'DEBIT') NOT NULL,
    amount_paise BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (txn_id) REFERENCES transactions(txn_id)
);

CREATE TABLE outbox_events (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status ENUM('PENDING', 'IN_PROGRESS', 'PUBLISHED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(100) NULL, -- Used for SKIP LOCKED worker identification
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Crucial index for polling high-throughput outbox fast
    INDEX idx_outbox_polling (status, created_at)
);

-- Note: In MariaDB to use SKIP LOCKED syntax, indexing rules require InnoDB (default).

-- Seed Data for Testing & Demonstration --
INSERT INTO users (vpa, status) VALUES ('user@upi', 'ACTIVE');
INSERT INTO account_balances (user_vpa, balance_paise) VALUES ('user@upi', 500000); -- 5000 INR

INSERT INTO agent_credentials (agent_id, agent_name, secret_ciphertext, secret_kms_key_id, status)
VALUES ('agent_demo_1', 'Demo AI Travel Agent', 'my_raw_db_secret', 'mock_kms', 'ACTIVE');

INSERT INTO mandates (umn, agent_id, user_vpa, daily_limit_paise, status)
VALUES ('umn_demo_123', 'agent_demo_1', 'user@upi', 100000, 'ACTIVE');
