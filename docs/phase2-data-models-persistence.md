# AgenticUPI — Phase 2: Data Models, Persistence & Isolation Strategy (LLD — DB Layer)

> **Complete MariaDB schema design, indexing strategy, concurrency control, and data partitioning** for a payment system handling 10K TPS with zero double-debit guarantees, optimistic + pessimistic locking, and a transactional outbox for exactly-once event delivery.

> **Scope note:** This is a personal portfolio and interview-preparation project that simulates a UPI PSP-style architecture. It is not affiliated with any bank/PSP, and it does not claim to represent internal production systems.

---

## Table of Contents

1. [Entity-Relationship Model](#1-entity-relationship-model)
2. [Complete MariaDB DDL Schema](#2-complete-mariadb-ddl-schema)
   - [2.1 `agent_credentials`](#21-agent_credentials)
   - [2.2 `mandates`](#22-mandates)
   - [2.3 `account_balances`](#23-account_balances)
   - [2.4 `ledger_entries`](#24-ledger_entries)
   - [2.5 `transactions`](#25-transactions)
   - [2.6 `outbox_events`](#26-outbox_events)
3. [Indexing Strategy & Query Optimization](#3-indexing-strategy--query-optimization)
   - [3.1 Index Catalog](#31-index-catalog)
   - [3.2 Query-to-Index Mapping](#32-query-to-index-mapping)
   - [3.3 Why These Indexes, Not Others](#33-why-these-indexes-not-others)
4. [Concurrency & Isolation Levels](#4-concurrency--isolation-levels)
   - [4.1 Isolation Level Selection](#41-isolation-level-selection)
   - [4.2 Locking Strategy Deep Dive](#42-locking-strategy-deep-dive)
   - [4.3 Deadlock Prevention](#43-deadlock-prevention)
   - [4.4 Connection Pool Sizing](#44-connection-pool-sizing)
5. [Transaction State Machine](#5-transaction-state-machine)
6. [Mandate State Machine](#6-mandate-state-machine)
7. [Data Retention & Archival Strategy](#7-data-retention--archival-strategy)
8. [Reconciliation & Ambiguous Downstream Outcomes](#8-reconciliation--ambiguous-downstream-outcomes)
9. [Security, Privacy & Secret Management](#9-security-privacy--secret-management)
10. [Interview Signal](#10-interview-signal)

---

## 1. Entity-Relationship Model

```
┌─────────────────────┐       ┌─────────────────────────┐
│  agent_credentials  │       │        mandates          │
├─────────────────────┤       ├─────────────────────────┤
│ PK  id              │       │ PK  id                  │
│     agent_id (UQ)   │◄──────│ FK  agent_id            │
│     agent_name      │  1:N  │     umn (UQ)            │
│     secret_hash     │       │     user_id             │
│     status          │       │     user_vpa            │
│     created_at      │       │     per_tx_limit_paise  │
│     updated_at      │       │     daily_limit_paise   │
└─────────────────────┘       │     allowed_categories  │
                              │     allowed_payee_vpas  │
                              │     status              │
                              │     valid_from          │
                              │     valid_until         │
                              │     version             │
                              │     created_at          │
                              │     updated_at          │
                              └───────────┬─────────────┘
                                          │ 1:N
                                          ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│   account_balances      │   │      transactions       │
├─────────────────────────┤   ├─────────────────────────┤
│ PK  id                  │   │ PK  id                  │
│     user_vpa (UQ)       │◄──│     payer_vpa           │
│     balance_paise       │   │     payee_vpa           │
│     currency            │   │ FK  mandate_id          │
│     version             │   │     idempotency_key (UQ)│
│     updated_at          │   │     amount_paise        │
└────────┬────────────────┘   │     currency            │
         │ 1:N                │     category            │
         ▼                    │     status              │
┌─────────────────────────┐   │     failure_reason      │
│    ledger_entries       │   │     agent_context       │
├─────────────────────────┤   │     trace_id           │
│ PK  id                  │   │     created_at          │
│     txn_id (FK)         │   │     updated_at          │
│     account_vpa         │   └───────────┬─────────────┘
│     entry_type          │               │ 1:N
│     amount_paise        │               ▼
│     direction           │   ┌─────────────────────────┐
│     balance_after_paise │   │     outbox_events       │
│     created_at          │   ├─────────────────────────┤
└─────────────────────────┘   │ PK  id                  │
                              │     event_id (UQ)       │
                              │     aggregate_type      │
                              │     aggregate_id        │
                              │     event_type          │
                              │     payload             │
                              │     status              │
                              │     retry_count         │
                              │     created_at          │
                              │     published_at        │
                              └─────────────────────────┘
```

---

## 2. Complete MariaDB DDL Schema

### Database & Character Set

```sql
CREATE DATABASE IF NOT EXISTS agentic_upi
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_bin;
-- utf8mb4_bin: binary collation for deterministic sorting on financial identifiers.
-- VPAs are normalized to lowercase at ingestion (see VPA handling note below),
-- but binary collation avoids locale-dependent surprises on other identifiers.

USE agentic_upi;

-- VPA Handling Note:
-- The UPI specification does not mandate case sensitivity for VPAs.
-- In practice, most PSPs (PhonePe, GPay, Paytm) normalize to lowercase.
-- We normalize all VPAs to lowercase at the ingestion gateway before any
-- storage or lookup. Tables store `normalized_vpa` (lowercase, used for
-- all indexes and lookups) and `display_vpa` where the original casing
-- matters for UI display (e.g., user_vpa in mandates, payer/payee in
-- transactions). The normalized form is the indexed, canonical form.

-- Global settings for the session (also set in my.cnf for persistence):
SET GLOBAL innodb_autoinc_lock_mode = 2;  -- "interleaved" — no table-level AUTO_INC lock
SET GLOBAL innodb_deadlock_detect = ON;
SET GLOBAL innodb_lock_wait_timeout = 5;  -- fail fast, don't queue for 50s (default)
```

---

### 2.1 `agent_credentials`

Stores registered AI agent identities and their HMAC shared secrets (hashed).

```sql
CREATE TABLE agent_credentials (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    agent_id                    VARCHAR(64)      NOT NULL COMMENT 'Public agent identifier (sent in X-Agent-Id header)',
    agent_name                  VARCHAR(128)     NOT NULL COMMENT 'Human-readable label (e.g., "ChatGPT Shopping Assistant")',
    
    -- HMAC Secret Storage
    secret_ciphertext           TEXT             NOT NULL COMMENT 'Encrypted payload of the actual HMAC secret',
    secret_kms_key_id           VARCHAR(128)     NOT NULL COMMENT 'Cloud KMS key ID used to encrypt the secret',
    secret_version              INT UNSIGNED     NOT NULL DEFAULT 1 COMMENT 'Secret version for rotation tracking',
    previous_secret_ciphertext  TEXT             NULL     COMMENT 'Encrypted payload of previous secret for rotation grace period',
    secret_fingerprint          CHAR(16)         NOT NULL COMMENT 'Truncated cryptographic hash for lookup/debugging without decrypting',
    
    status                      ENUM('ACTIVE', 'SUSPENDED', 'REVOKED')
                                                 NOT NULL DEFAULT 'ACTIVE',
    rate_limit_rps              INT UNSIGNED     NOT NULL DEFAULT 100
                                                 COMMENT 'Per-agent rate limit (requests per second)',
    created_at                  TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                  TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                 ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_id (agent_id),
    KEY idx_status (status)
) ENGINE=InnoDB
  ROW_FORMAT=DYNAMIC
  COMMENT='Registered AI agent identities and HMAC secrets';
```

**Design notes:**
- **HMAC secret storage:** We do NOT store one-way hashes (e.g. Argon2id) because HMAC verification requires the server to load the actual secret to compute the signature. Instead, the raw secret is encrypted at rest using a KMS key (`secret_ciphertext`). At verification time, the gateway decrypts it inside memory.
- **Rotation grace:** `previous_secret_ciphertext` allows seamless key rotation. When a user requests a new secret, the old one moves here and remains valid for a 24-hour grace period while the agent updates.
- `secret_fingerprint`: A short SHA-256 slice of the plaintext secret allowing support engineers to verify if a user is using "the secret ending in 8f4a" without decrypting it.
- `rate_limit_rps` is per-agent — allows fine-grained throttling of misbehaving agents without a code deploy.

---

### 2.2 `mandates`

The core authorization table. Each row represents a user's delegated permission for a specific agent to initiate payments.

```sql
CREATE TABLE mandates (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    umn                 CHAR(36)         NOT NULL COMMENT 'Unique Mandate Number (UUIDv7 — time-sortable)',
    user_id             BIGINT UNSIGNED  NOT NULL COMMENT 'FK to users table (Navi internal user ID)',
    user_vpa            VARCHAR(64)      NOT NULL COMMENT 'Payer VPA bound to this mandate',
    agent_id            VARCHAR(64)      NOT NULL COMMENT 'FK logical ref to agent_credentials.agent_id',

    -- Financial limits (stored in paise to avoid floating-point)
    per_tx_limit_paise  BIGINT UNSIGNED  NOT NULL COMMENT 'Max per-transaction amount in paise (≤50000 = ₹500)',
    daily_limit_paise   BIGINT UNSIGNED  NOT NULL COMMENT 'Rolling 24h aggregate cap in paise',

    -- Scope restrictions
    allowed_categories  JSON             NOT NULL DEFAULT '[]'
                                         COMMENT 'Merchant category whitelist, e.g. ["FOOD","TRANSPORT"]',
    allowed_payee_vpas  JSON             NOT NULL DEFAULT '[]'
                                         COMMENT 'Allowed payee VPAs. Empty [] = any payee within category.',

    -- Lifecycle
    status              ENUM('ACTIVE', 'PAUSED', 'REVOKED', 'EXPIRED')
                                         NOT NULL DEFAULT 'ACTIVE',
    valid_from          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    valid_until         TIMESTAMP(3)     NOT NULL COMMENT 'Mandate TTL — auto-expires after this time',

    -- Concurrency control
    version             INT UNSIGNED     NOT NULL DEFAULT 1
                                         COMMENT 'Optimistic lock version for concurrent updates',

    -- Partial unique constraint workaround for MariaDB (enforces one LIVE mandate)
    active_key          VARCHAR(128)     GENERATED ALWAYS AS (
                                            CASE
                                              WHEN status IN ('ACTIVE', 'PAUSED')
                                              THEN CONCAT(agent_id, ':', user_vpa)
                                              ELSE NULL
                                            END
                                         ) STORED,

    created_at          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                         ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_umn (umn),

    -- Hot-path lookup and uniqueness enforcement.
    -- Allows multiple historical REVOKED/EXPIRED rows because NULLs in unique keys do not collide.
    UNIQUE KEY uk_one_live_mandate (active_key),

    -- For resolving the active mandate (fast lookup)
    INDEX idx_resolve_mandate (agent_id, user_vpa, status),

    KEY idx_user_id (user_id),
    KEY idx_valid_until (valid_until),

    -- Constraint: per_tx_limit must be ≤ ₹500 (50000 paise)
    CONSTRAINT chk_per_tx_limit CHECK (per_tx_limit_paise > 0 AND per_tx_limit_paise <= 50000),
    -- Constraint: daily_limit must be ≥ per_tx_limit
    CONSTRAINT chk_daily_limit CHECK (daily_limit_paise >= per_tx_limit_paise),
    -- Constraint: valid_until must be in the future relative to valid_from
    CONSTRAINT chk_validity_window CHECK (valid_until > valid_from)
) ENGINE=InnoDB
  ROW_FORMAT=DYNAMIC
  COMMENT='User-defined delegated payment mandates for AI agents';
```

**Design notes:**
- **All monetary values in paise (integer).** Never `DECIMAL` or `FLOAT`. ₹500.00 = 50000 paise. Eliminates floating-point rounding errors in aggregate calculations. This is standard practice at every Indian payment company.
- **UUIDv7 for UMN.** Time-sortable, globally unique, no coordination needed across pods. The `CHAR(36)` storage cost is acceptable — mandate count is in millions, not billions.
- **`uk_one_live_mandate` generated column workaround.** This is critical. A straightforward `UNIQUE(agent_id, user_vpa, status)` breaks if a user revokes a mandate and then later creates and revokes a second one — the second revocation would fail on a duplicate key. MariaDB lacks Postgres-style partial unique indexes (`WHERE status = 'ACTIVE'`). The `active_key` generated column sets itself to `NULL` for terminal states; since multiple `NULL`s are allowed in a unique index, historical revoked rows can coexist, while enforcing exactly one live ('ACTIVE' or 'PAUSED') mandate.
- **`allowed_categories` as JSON, not a junction table.** At ≤20 categories per mandate, a junction table adds join overhead on every request. JSON column with application-level validation is the pragmatic choice. MariaDB 10.6+ supports `JSON_CONTAINS()` for queries if needed, but the Policy Engine deserializes and checks in Java (faster than a DB-side JSON scan).
- **`version` column for optimistic concurrency control.** When the user modifies a mandate in the app, the update includes `WHERE version = :expected`. This prevents lost updates from concurrent modifications (e.g., user pauses from phone while admin revokes from dashboard).

---

### 2.3 `account_balances`

Materialized balance per VPA. This is the authoritative debit source.

```sql
CREATE TABLE account_balances (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_vpa        VARCHAR(64)      NOT NULL COMMENT 'UPI Virtual Payment Address',
    balance_paise   BIGINT           NOT NULL DEFAULT 0
                                     COMMENT 'Current available balance in paise (can be negative in reconciliation edge cases)',
    currency        CHAR(3)          NOT NULL DEFAULT 'INR',
    version         BIGINT UNSIGNED  NOT NULL DEFAULT 1
                                     COMMENT 'Optimistic lock — incremented on every balance mutation',
    updated_at      TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                     ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_vpa (user_vpa),

    -- Balance should not go below zero under normal operations.
    -- Using BIGINT (signed) instead of UNSIGNED to allow temporary negative
    -- during reconciliation corrections — the CHECK constraint is on the
    -- application layer, not the DB, to avoid blocking reconciliation jobs.
    KEY idx_balance_low (balance_paise) COMMENT 'For monitoring: find accounts near zero'
) ENGINE=InnoDB
  ROW_FORMAT=COMPACT
  COMMENT='Materialized account balances — authoritative debit source';
```

**Design notes:**
- **`BIGINT` (signed), not `BIGINT UNSIGNED` for balance.** A reconciliation correction may temporarily push balance negative. We enforce `balance ≥ 0` in the application `@Transactional` block, not via `CHECK` constraint, so that reconciliation batch jobs can apply corrections without constraint violations.
- **`version` as `BIGINT UNSIGNED`.** At 10K TPS, an `INT UNSIGNED` (max ~4.2B) would overflow in ~5 days if every TPS hits the same VPA. `BIGINT` gives headroom for centuries. In practice, version increments are distributed across millions of VPAs, but defensive sizing costs nothing here (8 bytes vs 4 bytes per row, ~2M rows = 8MB overhead).
- **No `created_at`.** Account balances are created once during user onboarding and updated millions of times. The creation timestamp is in the users table (not in scope for AgenticUPI). `updated_at` matters for debugging stale balance issues.

---

### 2.4 `ledger_entries`

Immutable, append-only double-entry ledger. Every balance mutation has a corresponding ledger row. This is the audit trail and reconciliation source.

```sql
CREATE TABLE ledger_entries (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    entry_id            CHAR(36)         NOT NULL COMMENT 'UUIDv7 — globally unique, time-sortable',
    txn_id              CHAR(36)         NOT NULL COMMENT 'FK to transactions.txn_id — groups entries together',
    
    -- Explicit Double-Entry modeling tracking both sides of the movement
    ledger_account      VARCHAR(64)      NOT NULL COMMENT 'The account this entry affects (user_vpa, suspense_account, etc.)',
    debit_credit        ENUM('DEBIT', 'CREDIT') 
                                         NOT NULL,
    amount_paise        BIGINT UNSIGNED  NOT NULL COMMENT 'Always positive',
    balance_after_paise BIGINT           NOT NULL COMMENT 'Running balance snapshot after this entry',
    
    -- Reconciliation tracking
    correlation_id      VARCHAR(64)      NULL     COMMENT 'Used for tying a credit-back reversal to its original debit',
    reference_type      VARCHAR(32)      NULL     COMMENT 'e.g., AGENT_PAYMENT, MANDATE_TOPUP, RECONCILIATION',
    reference_id        VARCHAR(64)      NULL     COMMENT 'Mandate UMN, reconciliation batch ID, etc.',
    
    created_at          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_entry_id (entry_id),

    -- "Show me all ledger entries for this account, newest first"
    KEY idx_account_created (ledger_account, created_at DESC),

    -- "Find all entries for this transaction" (debit + credit pair)
    KEY idx_txn_id (txn_id),

    -- Constraint: amount must be positive
    CONSTRAINT chk_amount_positive CHECK (amount_paise > 0)
) ENGINE=InnoDB
  ROW_FORMAT=DYNAMIC
  COMMENT='Immutable append-only double-entry ledger — never UPDATE or DELETE';
```

**Design notes:**
- **Ledger balancing invariant:** While our scope simulates a single-sided payer wallet, we model the `debit_credit` and `ledger_account` to support a true double-entry system. In a full implementation, every transaction has exactly two entries (DEBIT payer, CREDIT payee) which sum to zero. Our prototype focuses on the DEBIT side and writes the CREDIT to a generic `UPI_OUTBOUND_SUSPENSE` account to satisfy the double-entry invariant.
- **Append-only. No `UPDATE`, no `DELETE`, ever.** This table is the financial source of truth. Corrections are done via compensating entries (`REVERSAL_DEBIT` / `REVERSAL_CREDIT`), not by modifying existing rows. This is a regulatory requirement for financial ledgers (and makes reconciliation possible).
- **`balance_after_paise` is a snapshot.** After each entry, we record what the balance was. This allows reconstructing account history without replaying the entire ledger. Critical for dispute resolution: "What was the balance at 3:47 PM on March 15th?"
- **`idx_account_created` with `DESC` ordering.** The most common query is "show recent entries for this account." InnoDB stores B-Tree indexes in ascending order by default; the `DESC` hint ensures the optimizer can do a backward scan efficiently. In MariaDB 10.8+, descending indexes are natively supported.
- **No foreign key constraint to `transactions`.** Deliberate. FK constraints add locking overhead on the parent table during child inserts. At 10K TPS, this creates contention on the `transactions` table's primary key index. Referential integrity is enforced at the application layer — both inserts happen in the same `@Transactional` block.

---

### 2.5 `transactions`

Each row represents a single payment attempt — successful or failed.

```sql
CREATE TABLE transactions (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    txn_id              CHAR(36)         NOT NULL COMMENT 'UUIDv7 — the canonical transaction identifier',
    idempotency_key     VARCHAR(128)     NOT NULL COMMENT 'Client-generated, unique per payment intent',
    request_hash        CHAR(64)         NOT NULL COMMENT 'SHA-256 hash of the canonical request payload for idempotency collision detection',
    mandate_id          BIGINT UNSIGNED  NOT NULL COMMENT 'FK to mandates.id',
    mandate_umn         CHAR(36)         NOT NULL COMMENT 'Denormalized for audit queries without join',

    payer_vpa           VARCHAR(64)      NOT NULL,
    payee_vpa           VARCHAR(64)      NOT NULL,
    amount_paise        BIGINT UNSIGNED  NOT NULL,
    currency            CHAR(3)          NOT NULL DEFAULT 'INR',
    category            VARCHAR(32)      NOT NULL COMMENT 'Merchant category (FOOD, TRANSPORT, etc.)',

    status              ENUM(
                            'INITIATED', 'POLICY_APPROVED', 'LEDGER_RESERVED',
                            'DOWNSTREAM_PENDING', 'SUCCESS', 'FAILED', 
                            'REVERSED', 'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW_REQUIRED'
                        ) NOT NULL DEFAULT 'INITIATED',
    failure_reason      VARCHAR(64)      NULL     COMMENT 'Machine-readable failure code (null on success)',
    failure_detail      VARCHAR(256)     NULL     COMMENT 'Human-readable detail (null on success)',

    -- Agent context (untrusted, for audit only — never used in business logic)
    agent_id            VARCHAR(64)      NOT NULL,
    agent_context       TEXT             NULL     COMMENT 'Free-text context from the agent. Untrusted. Audit only.',

    -- Observability
    trace_id            VARCHAR(64)      NULL     COMMENT 'Jaeger/OpenTelemetry distributed trace ID',

    created_at          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                         ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_id (txn_id),
    UNIQUE KEY uk_idempotency_key (idempotency_key),

    -- "Show me all transactions for this payer, most recent first"
    KEY idx_payer_created (payer_vpa, created_at DESC),

    -- "Show me all transactions under this mandate"
    KEY idx_mandate_id (mandate_id, created_at DESC),

    -- "Find all transactions for this payee" (merchant reporting)
    KEY idx_payee_vpa (payee_vpa, created_at DESC),

    -- "Find all failed transactions in the last hour" (ops dashboard)
    KEY idx_status_created (status, created_at DESC),

    -- Constraint: amount bounds
    CONSTRAINT chk_txn_amount CHECK (amount_paise > 0 AND amount_paise <= 50000)
) ENGINE=InnoDB
  ROW_FORMAT=DYNAMIC
  COMMENT='Payment transaction records — one row per payment attempt';
```

**Design notes:**
- **`uk_idempotency_key` and `request_hash`.** The combination of idempotency key and request payload hash prevents "silent data corruption". If an agent re-uses an idempotency key with a new payload (different amount/payee) — either maliciously or due to a client retry library bug — the gateway compares the stored `request_hash` to the incoming hash. If they mismatch, it throws `409 IDEMPOTENCY_CONFLICT` rather than returning the success response for the old payload.
- **`mandate_umn` is denormalized.** Yes, it's technically redundant (can be joined via `mandate_id`). But audit queries ("show all transactions for mandate UMN X") are frequent, and avoiding a join on a table with millions of rows is worth the 36 bytes per row.
- **`failure_reason` is an enum-like string (e.g., `INSUFFICIENT_BALANCE`, `VELOCITY_EXCEEDED`, `MANDATE_EXPIRED`), not a freeform field.** Machine-readable for metrics aggregation. `failure_detail` carries the human-readable explanation.
- **`agent_context` is `TEXT`, not `VARCHAR`.** Agent context can be arbitrarily long (LLM outputs). Stored for audit trail, never parsed or used in queries. `TEXT` is stored off-page in InnoDB `DYNAMIC` row format, so it doesn't bloat the primary key's clustered index leaf pages.

---

### 2.6 `outbox_events`

Transactional outbox for reliable event delivery to Kafka.

```sql
CREATE TABLE outbox_events (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)         NOT NULL COMMENT 'UUIDv7 — globally unique event identifier',
    aggregate_type  VARCHAR(32)      NOT NULL COMMENT 'MANDATE or TRANSACTION',
    aggregate_id    VARCHAR(64)      NOT NULL COMMENT 'UMN for mandates, txn_id for transactions',
    event_type      VARCHAR(64)      NOT NULL COMMENT 'e.g., MANDATE_CREATED, PAYMENT_SUCCESS, MANDATE_REVOKED',
    partition_key   VARCHAR(64)      NOT NULL COMMENT 'Kafka partition key (= payer_vpa for ordering)',

    payload         JSON             NOT NULL COMMENT 'Full event payload as structured JSON',

    status          ENUM('PENDING', 'PUBLISHED', 'FAILED')
                                     NOT NULL DEFAULT 'PENDING',
    retry_count     TINYINT UNSIGNED NOT NULL DEFAULT 0,
    max_retries     TINYINT UNSIGNED NOT NULL DEFAULT 5,

    created_at      TIMESTAMP(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at    TIMESTAMP(3)     NULL     COMMENT 'When the relay successfully produced to Kafka',

    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),

    -- The relay query: "give me the next batch of unpublished events, oldest first"
    -- SKIP LOCKED ensures multiple relay instances don't pick the same rows.
    KEY idx_status_created (status, created_at ASC),

    -- "Find all events for this aggregate" (debugging, reprocessing)
    KEY idx_aggregate (aggregate_type, aggregate_id, created_at ASC)
) ENGINE=InnoDB
  ROW_FORMAT=DYNAMIC
  COMMENT='Transactional outbox — events co-committed with business data, relayed to Kafka';
```

**Design notes:**
- **`idx_status_created` is the relay's hot index.** At 10K TPS, a single polling worker doing `LIMIT 100` every 5 seconds is wildly insufficient. The outbox relay relies on:
  - Multiple worker threads/pods polling continuously (e.g., every 100-250ms).
  - High batch sizes (`LIMIT 1000` to `LIMIT 5000` per worker).
  - Shard-aware routing where necessary to distribute Kafka producing.
  - The query leverages `FOR UPDATE SKIP LOCKED`.
  ```sql
  SELECT * FROM outbox_events
  WHERE status = 'PENDING'
  ORDER BY created_at ASC
  LIMIT 5000
  FOR UPDATE SKIP LOCKED;
  ```
  The composite index `(status, created_at ASC)` makes this an index-only range scan on `status='PENDING'` rows, ordered by creation time. `SKIP LOCKED` (MariaDB 10.6+) allows concurrent workers to pull distinct batches simultaneously without blocking each other. Ultimately, Debezium CDC (listening directly to the binlog) is the preferred architectural pattern at >5K TPS to avoid DB polling overhead entirely, but `SKIP LOCKED` scales very well as a V1 architecture.

- **`partition_key` is stored explicitly.** The relay uses this as the Kafka message key when producing. By storing it in the outbox row (= `payer_vpa`), the relay doesn't need to parse the JSON payload to determine the partition key. This is a micro-optimization but important at 10K events/second.

- **`retry_count` + `max_retries`.** If Kafka produce fails, the relay increments `retry_count`. After `max_retries`, the event is marked `FAILED` and an alert fires. Failed events are investigated manually — they indicate a systemic issue (Kafka down for extended periods).

- **Payload as JSON, not as a serialized blob.** JSON allows querying event history for debugging (`SELECT * FROM outbox_events WHERE JSON_EXTRACT(payload, '$.amount_paise') > 10000`). The performance cost is negligible — the relay reads and forwards the payload verbatim; it doesn't parse it.

---

## 3. Indexing Strategy & Query Optimization

### 3.1 Index Catalog

Every index has a justification tied to a specific query or constraint. No speculative indexes.

```
┌────────────────────────┬──────────────────────────────────┬───────────┬───────────────────────────────────────────────┐
│ Table                  │ Index Name                       │ Type      │ Purpose                                       │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ agent_credentials      │ uk_agent_id                      │ UNIQUE    │ Gateway HMAC lookup by agent_id                │
│ agent_credentials      │ idx_status                       │ B-TREE    │ Filter active agents for admin dashboard       │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ mandates               │ uk_umn                           │ UNIQUE    │ Mandate lookup by UMN (kill-switch, audit)     │
│ mandates               │ uk_agent_vpa_active              │ UNIQUE    │ HOT PATH: mandate resolution per request       │
│ mandates               │ idx_user_id                      │ B-TREE    │ "Show my mandates" in UI                       │
│ mandates               │ idx_valid_until                  │ B-TREE    │ Expiration batch job                           │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ account_balances       │ uk_user_vpa                      │ UNIQUE    │ HOT PATH: balance lookup for debit             │
│ account_balances       │ idx_balance_low                  │ B-TREE    │ Ops monitoring: low-balance alerts              │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ ledger_entries         │ uk_entry_id                      │ UNIQUE    │ Idempotent ledger writes (reconciliation)      │
│ ledger_entries         │ idx_account_created              │ B-TREE    │ Account statement queries                      │
│ ledger_entries         │ idx_txn_id                       │ B-TREE    │ Lookup all entries for a transaction            │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ transactions           │ uk_txn_id                        │ UNIQUE    │ Transaction lookup by ID                       │
│ transactions           │ uk_idempotency_key               │ UNIQUE    │ DB-level duplicate prevention (final guard)     │
│ transactions           │ idx_payer_created                │ B-TREE    │ User's transaction history                     │
│ transactions           │ idx_mandate_id                   │ B-TREE    │ Transactions per mandate                       │
│ transactions           │ idx_payee_vpa                    │ B-TREE    │ Merchant transaction reporting                 │
│ transactions           │ idx_status_created               │ B-TREE    │ Ops dashboard: failed/pending transactions     │
├────────────────────────┼──────────────────────────────────┼───────────┼───────────────────────────────────────────────┤
│ outbox_events          │ uk_event_id                      │ UNIQUE    │ Deduplication on consumer side                 │
│ outbox_events          │ idx_status_created               │ B-TREE    │ HOT PATH: outbox relay polling query           │
│ outbox_events          │ idx_aggregate                    │ B-TREE    │ Event history for an aggregate (debugging)     │
└────────────────────────┴──────────────────────────────────┴───────────┴───────────────────────────────────────────────┘
```

### 3.2 Query-to-Index Mapping

Every hot-path query and its index usage:

```sql
-- Q1: Mandate resolution (every payment request, P95 <3ms on cache miss)
-- Used by: PolicyEngine.resolveMandate()
SELECT id, umn, per_tx_limit_paise, daily_limit_paise, allowed_categories,
       allowed_payee_vpas, status, valid_until
FROM mandates
WHERE agent_id = ? AND user_vpa = ? AND status = 'ACTIVE'
  AND valid_until > NOW(3);
-- Index: uk_agent_vpa_active (agent_id, user_vpa, status)
-- Plan:  const lookup via unique key → 1 row, no scan.
-- valid_until filtered post-fetch (single row, negligible cost).

-- Q2: Balance debit (inside @Transactional, P95 <3ms)
-- Used by: LedgerService.debit()
SELECT balance_paise, version FROM account_balances
WHERE user_vpa = ?
FOR UPDATE;
-- Index: uk_user_vpa
-- Plan:  const lookup → row lock on the single matching row.
-- FOR UPDATE acquires an X-lock on the index record AND the clustered
-- index record. No gap lock because it's a unique key eq lookup.

-- Q3: Idempotency final guard (inside @Transactional)
-- Used by: TransactionService.createTransaction()
INSERT INTO transactions (txn_id, idempotency_key, ...) VALUES (...);
-- Index: uk_idempotency_key
-- Plan:  unique key check before insert. If duplicate →
--        DuplicateKeyException → caught, return original result.

-- Q4: Outbox relay poll (background, every 5s)
-- Used by: OutboxRelayService.pollAndPublish()
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
-- Index: idx_status_created (status, created_at ASC)
-- Plan:  range scan on status='PENDING', ordered by created_at.
--        SKIP LOCKED: if another relay instance holds a lock,
--        skip that row and take the next one. No blocking.

-- Q5: User transaction history (UI, paginated)
-- Used by: TransactionController.getHistory()
SELECT txn_id, payee_vpa, amount_paise, status, created_at
FROM transactions
WHERE payer_vpa = ?
ORDER BY created_at DESC
LIMIT 20 OFFSET ?;
-- Index: idx_payer_created (payer_vpa, created_at DESC)
-- Plan:  range scan on payer_vpa, ordered by created_at DESC.
--        LIMIT 20 → at most 20 rows fetched from index.
```

### 3.3 Why These Indexes, Not Others

| Considered but rejected                     | Reason                                                                                                                                                                     |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Composite index on `transactions(payer_vpa, status, created_at)` | Over-indexing. The `idx_payer_created` index already covers the common query. Adding `status` helps only the "show me failed transactions for user X" query, which is rare (ops dashboard, not hot path). The index maintenance cost on the write path (10K inserts/s) isn't justified. |
| Full-text index on `agent_context`          | `agent_context` is untrusted free-text, never searched in production. Audit queries use `txn_id` or `mandate_umn` to locate specific transactions. Full-text indexing would bloat the table and slow writes with no benefit. |
| Foreign key constraints (all tables)        | Deliberately omitted. InnoDB FK constraints add shared locks on parent rows during child inserts. At 10K TPS, this creates lock contention on `mandates` and `account_balances` primary keys. Referential integrity is enforced at the application layer within `@Transactional` boundaries. |
| Index on `mandates.allowed_categories`      | JSON columns can't be efficiently indexed with standard B-Tree indexes. MariaDB virtual column + index is possible but not worth it — category validation happens in Java after deserializing the mandate (which is cached in Redis anyway). |
| Covering index on `transactions` for the full response payload | Covering indexes that include large columns (`agent_context`) would be enormous. The current approach (index lookup → clustered index fetch for remaining columns) is faster at this row size. |

---

## 4. Concurrency & Isolation Levels

### 4.1 Isolation Level Selection

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                     ISOLATION LEVEL: READ COMMITTED                              │
│                     (MariaDB default is REPEATABLE READ — we change it)          │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Why not REPEATABLE READ (the default)?                                          │
│  ─────────────────────────────────────                                           │
│  REPEATABLE READ in InnoDB uses gap locks for range scans.                       │
│  Our outbox relay query:                                                         │
│                                                                                  │
│    SELECT * FROM outbox_events WHERE status='PENDING'                            │
│    ORDER BY created_at LIMIT 100 FOR UPDATE;                                     │
│                                                                                  │
│  Under REPEATABLE READ, this acquires:                                           │
│    1. X-locks on the 100 matching rows ✓                                         │
│    2. Gap locks on the ranges BETWEEN those rows ✗                               │
│                                                                                  │
│  The gap locks block INSERTs into the outbox_events table by the                 │
│  main payment transaction. At 10K TPS, this causes thread pool                   │
│  starvation — payment transactions wait for the relay to finish                  │
│  its batch before they can insert new outbox rows.                               │
│                                                                                  │
│  Under READ COMMITTED:                                                           │
│    1. X-locks on the 100 matching rows ✓                                         │
│    2. No gap locks ✓                                                             │
│    3. New INSERTs into outbox_events proceed without blocking ✓                  │
│                                                                                  │
│  Trade-off: READ COMMITTED allows phantom reads within a                         │
│  transaction. This is acceptable because:                                        │
│    • Our payment transaction reads exactly ONE row from                           │
│      account_balances (by unique key) — no phantom possible.                     │
│    • We INSERT into ledger_entries, transactions, outbox_events                   │
│      — writes don't suffer from phantom reads.                                   │
│    • The outbox relay processes each event idempotently                           │
│      (by event_id) — a phantom row just gets picked up in                        │
│      the next polling cycle.                                                     │
│                                                                                  │
│  Applied via:                                                                    │
│    SET GLOBAL TRANSACTION ISOLATION LEVEL READ COMMITTED;                        │
│    -- Also in my.cnf: transaction-isolation = READ-COMMITTED                     │
│                                                                                  │
│  Also, READ COMMITTED enables SKIP LOCKED semantics in                           │
│  MariaDB 10.6+, which we use in the outbox relay.                                │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Locking Strategy Deep Dive

```
Payment Transaction — Lock Acquisition Order
═════════════════════════════════════════════

BEGIN;

  ┌─────────────────────────────────────────────────────────────┐
  │ SELECT balance_paise, version FROM account_balances         │
  │ WHERE user_vpa = :payer_vpa FOR UPDATE;                     │
  │                                                             │
  │ Lock type:  X-lock (exclusive) on the index record          │
  │             for uk_user_vpa = :payer_vpa                    │
  │             + X-lock on the clustered index (primary key)   │
  │                                                             │
  │ Lock scope: Single row (unique key lookup = record lock,    │
  │             no gap lock even under REPEATABLE READ)         │
  │                                                             │
  │ Lock duration: Held until COMMIT                            │
  │                                                             │
  │ Concurrency effect: All other transactions targeting the    │
  │ same user_vpa QUEUE here. This is intentional — it          │
  │ serializes balance mutations for a single account.          │
  │ Different VPAs lock different rows → no contention.         │
  └─────────────────────────────────────────────────────────────┘
              │
              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ -- Application-level check                                  │
  │ if (balance_paise < amount_paise) {                         │
  │     ROLLBACK;  // releases the lock immediately             │
  │     throw InsufficientBalanceException;                     │
  │ }                                                           │
  └─────────────────────────────────────────────────────────────┘
              │
              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ UPDATE account_balances                                     │
  │ SET balance_paise = balance_paise - :amount_paise,          │
  │     version = version + 1                                   │
  │ WHERE user_vpa = :payer_vpa AND version = :expected_version;│
  │                                                             │
  │ Lock type:  Already held from FOR UPDATE (no additional     │
  │             lock acquisition — same row, same X-lock)       │
  │                                                             │
  │ Optimistic check: If rows_affected == 0, the version        │
  │ changed between the SELECT and UPDATE (impossible within    │
  │ this transaction because we hold the X-lock, but defends    │
  │ against bugs like connection pool leak + reconnect).        │
  └─────────────────────────────────────────────────────────────┘
              │
              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ INSERT INTO ledger_entries (...);                            │
  │ INSERT INTO transactions (...);                             │
  │ INSERT INTO outbox_events (...);                            │
  │                                                             │
  │ Lock type: InnoDB takes implicit X-locks on the new index   │
  │            records during INSERT. Under READ COMMITTED,     │
  │            no gap locks — concurrent inserts to different    │
  │            keys proceed without blocking.                   │
  │                                                             │
  │ Auto-increment: innodb_autoinc_lock_mode = 2 (interleaved) │
  │ → no table-level AUTO_INCREMENT lock. Each INSERT acquires  │
  │ a lightweight mutex just long enough to allocate the next   │
  │ auto-inc value. IDs may have gaps — acceptable.             │
  └─────────────────────────────────────────────────────────────┘
              │
              ▼
COMMIT;  -- All locks released atomically. fsync + group commit.
```

### 4.3 Deadlock Prevention

```
Rule 1: Single-Resource Lock Ordering
──────────────────────────────────────
The payment transaction locks exactly ONE resource: account_balances
for the payer_vpa. There is no second lock acquisition on a different
row in account_balances (we don't credit the payee in the same
transaction — that's a downstream CBS operation in real UPI).

Since we lock only one row, circular wait is impossible → deadlock
is impossible within our transaction logic.

Rule 2: Lock Ordering Between Tables
─────────────────────────────────────
Within the transaction, table access order is always:
  1. account_balances (SELECT FOR UPDATE — exclusive lock)
  2. ledger_entries (INSERT — implicit lock on new row)
  3. transactions (INSERT — implicit lock on new row)
  4. outbox_events (INSERT — implicit lock on new row)

This order is enforced by the service layer code structure.
No transaction ever acquires locks in a different order.

Rule 3: Outbox Relay Uses SKIP LOCKED
──────────────────────────────────────
The outbox relay's SELECT FOR UPDATE SKIP LOCKED never waits
for a lock held by the payment transaction (or another relay
instance). If a row is locked, it's skipped. No deadlock possible.

Rule 4: innodb_lock_wait_timeout = 5
─────────────────────────────────────
Even if a deadlock were somehow introduced (e.g., schema migration
bug), InnoDB detects it within innodb_deadlock_detect cycle (<1s)
and rolls back the transaction with the least undo work. The 5s
wait timeout is a safety net for non-deadlock lock waits (e.g.,
a long-running query holding a row lock).
```

### 4.4 Connection Pool Sizing

```
┌──────────────────────────────────────────────────────────────────────┐
│                    CONNECTION POOL FORMULA                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Target: 10,000 TPS with P99 < 80ms                                 │
│                                                                      │
│  Avg transaction duration (DB portion): ~10ms (P50)                  │
│  Pool connections needed = TPS × (duration_s)                        │
│                          = 10000 × 0.010                             │
│                          = 100 connections active at any instant      │
│                                                                      │
│  With 6-8 Spring Boot pods:                                          │
│    Per-pod pool size = 100 / 6 ≈ 17                                  │
│    With 3x headroom for burst and P99 variance: ~50/pod              │
│                                                                      │
│  HikariCP configuration:                                             │
│    spring.datasource.hikari.maximum-pool-size = 50                   │
│    spring.datasource.hikari.minimum-idle = 10                        │
│    spring.datasource.hikari.idle-timeout = 300000        # 5 min     │
│    spring.datasource.hikari.max-lifetime = 1800000       # 30 min    │
│    spring.datasource.hikari.connection-timeout = 2000    # 2s        │
│    spring.datasource.hikari.leak-detection-threshold = 5000  # 5s    │
│                                                                      │
│  ProxySQL max_connections: 500                                       │
│  MariaDB max_connections: 600 (headroom for admin, monitoring)       │
│                                                                      │
│  Why HikariCP, not Tomcat JDBC pool?                                 │
│  HikariCP uses a lock-free ConcurrentBag for connection checkout,    │
│  achieving ~1µs checkout latency vs ~10µs for Tomcat JDBC pool.      │
│  At 10K TPS, this saves 90µs × 10K = 900ms of aggregate wait/second.│
│                                                                      │
│  ProxySQL connection multiplexing:                                   │
│  6 pods × 50 connections = 300 pool connections.                     │
│  ProxySQL multiplexes these onto ~200 actual MariaDB connections     │
│  (because not all are active simultaneously). This reduces           │
│  MariaDB's per-connection memory overhead (~10MB/conn).              │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. Transaction State Machine

```
                            ┌───────────────┐
                            │   INITIATED   │
                            │  (Intent Rx)  │
                            └──────┬────────┘
                                   │
                                   ▼
                            ┌───────────────┐
                            │POLICY_APPROVED│
                            │(Mandate + Vel.│
                            │ Check Passed) │
                            └──────┬────────┘
                                   │
                   ┌───────────────┼───────────────┐
                   │               │               │
            Balance OK      Insufficient     Optimistic lock
            (Lock Acqd)     balance          version conflict
                   │               │               │
                   ▼               ▼               ▼
            ┌───────────────┐┌──────────┐    ┌──────────┐
            │LEDGER_RESERVED││  FAILED  │    │  FAILED  │
            │(Funds Debited)││ INSUFF.  │    │ CONFLICT │
            └──────┬────────┘│ BALANCE  │    │ (retry)  │
                   │         │ Rollback │    │ Rollback │
                   ▼         └──────────┘    └──────────┘
            ┌───────────────┐
            │DOWNSTREAM_    │
            │PENDING        │
            │(Sent to CBS/  │
            │ NPCI)         │
            └──────┬────────┘
                   │
         CBS replies synchronously OR Webhook received
         OR Poller picks up missing callback
                   │
       ┌───────────┼───────────┬───────────┐
       │           │           │           │
       ▼           ▼           ▼           ▼
 ┌─────────┐ ┌─────────┐ ┌───────────┐ ┌─────────────┐
 │ SUCCESS │ │ REVERSED│ │ RECONCIL- │ │ MANUAL_     │
 │ (CBS OK)│ │ (CBS    │ │ IATION_   │ │ REVIEW_     │
 │         │ │ FAILED, │ │ REQUIRED  │ │ REQUIRED    │
 │ Outbox  │ │ Credited│ │ (Timeout, │ │ (Edge case) │
 │ event)  │ │ back)   │ │ No Tries  │ │             │
 └─────────┘ └─────────┘ │ Left)     │ └─────────────┘
                         └───────────┘

State Transition Rules:
───────────────────────
  INITIATED           → POLICY_APPROVED         : Cache validation passed
  POLICY_APPROVED     → LEDGER_RESERVED         : DB Debit succeeded
  LEDGER_RESERVED     → DOWNSTREAM_PENDING      : Sent upstream to real UPI switch
  DOWNSTREAM_PENDING  → SUCCESS                 : Terminal happy path
  DOWNSTREAM_PENDING  → REVERSED                : Terminal failure (CBS rejected, credit-back done)
  DOWNSTREAM_PENDING  → RECONCILIATION_REQUIRED : Timed out without CBS response
  RECONCILIATION_REQ  → SUCCESS / REVERSED      : Background job queries CBS
```

---

## 6. Mandate State Machine

```
                            ┌──────────────┐
                            │   ACTIVE     │
                  ┌────────▶│              │◀────────┐
                  │         │  Accepting   │         │
                  │         │  payments    │         │
                  │         └──┬────┬──────┘         │
                  │            │    │                 │
               Resume      Pause  Revoke         User edits
               (user)     (user)  (user/         (version++)
                  │            │   system)
                  │            ▼      │
                  │     ┌──────────┐  │
                  └─────│  PAUSED  │  │
                        │          │  │
                        │  Holding │  │
                        │  —no pay │  │
                        └────┬─────┘  │
                             │        │
                          Revoke      │
                          (user/      │
                           system)    │
                             │        │
                             ▼        ▼
                        ┌──────────────┐
                        │   REVOKED    │
                        │              │
                        │  Kill-switch │
                        │  terminal    │
                        │  (Redis DEL  │
                        │   + DB sync) │
                        └──────────────┘

                        ┌──────────────┐
                        │   EXPIRED    │
                        │              │
                        │  TTL reached │
                        │  (batch job  │
                        │   or on-read │
                        │   check)     │
                        └──────────────┘

State Transition Rules:
───────────────────────
  ACTIVE  → PAUSED   : User taps "Pause" in app. Mandate cached in Redis
                        is deleted (fail-closed until resumed).
  ACTIVE  → REVOKED  : User taps "Revoke" (kill-switch). Immediate.
                        Redis DEL + DB UPDATE in same request.
  ACTIVE  → EXPIRED  : Batch job checks valid_until < NOW(), OR
                        on-read check during mandate resolution.
  PAUSED  → ACTIVE   : User taps "Resume". Mandate re-cached in Redis.
  PAUSED  → REVOKED  : User can revoke a paused mandate.
  REVOKED → (terminal): Immutable. Cannot be un-revoked. User must
                         create a new mandate.
  EXPIRED → (terminal): Immutable. User must create a new mandate.

Redis Cache Behavior per State:
───────────────────────────────
  ACTIVE  : Cache present (5-min TTL, refreshed on read)
  PAUSED  : Cache DELETED (ensures fail-closed — no payments possible)
  REVOKED : Cache DELETED + tombstone key `revoked:{umn}` (60s TTL)
            for fast rejection of in-flight requests
  EXPIRED : Cache DELETED (on-read expiry or batch cleanup)
```

---

## 7. Data Retention & Archival Strategy

```
┌────────────────────────┬───────────────┬─────────────────────────────────────────────────┐
│ Table                  │ Retention     │ Archival Strategy                               │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ transactions           │ 90 days hot   │ After 90 days: move to archive_transactions     │
│                        │ 7 years cold  │ (same schema, separate tablespace, compressed). │
│                        │               │ Partitioned by RANGE on created_at (monthly).   │
│                        │               │ DROP PARTITION is O(1) — no row-by-row DELETE.   │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ ledger_entries         │ 90 days hot   │ Same as transactions. Monthly partitions.        │
│                        │ 7 years cold  │ Never delete — regulatory requirement.           │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ outbox_events          │ 7 days        │ After PUBLISHED for >7 days: DELETE.             │
│                        │               │ FAILED events retained until manual review.      │
│                        │               │ No archival — events are in Kafka (7-day         │
│                        │               │ retention) and consumers' own stores.             │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ mandates               │ Indefinite    │ REVOKED/EXPIRED mandates retained for audit.     │
│                        │               │ Low cardinality (~millions) — no archival needed. │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ account_balances       │ Indefinite    │ One row per VPA. Never grows beyond user count.   │
│                        │               │ No archival needed.                               │
├────────────────────────┼───────────────┼─────────────────────────────────────────────────┤
│ agent_credentials      │ Indefinite    │ Low cardinality. No archival.                     │
└────────────────────────┴───────────────┴─────────────────────────────────────────────────┘

Monthly Partition Example (transactions):
─────────────────────────────────────────
ALTER TABLE transactions
  PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
    PARTITION p_2026_01 VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01')),
    PARTITION p_2026_02 VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01')),
    -- ... auto-managed by a scheduled job that adds next month's partition
    PARTITION p_future VALUES LESS THAN MAXVALUE
  );

-- Archival: detach old partition, attach to archive table
ALTER TABLE transactions EXCHANGE PARTITION p_2026_01
  WITH TABLE archive_transactions_2026_01;
-- O(1) operation — just metadata update, no row copying.

**Partitioning Caveat:** In MariaDB/MySQL, if a table is partitioned, all unique keys (including the primary key) must include the partition column (`created_at`). For `transactions`, this would require changing `UNIQUE KEY (idempotency_key)` to `UNIQUE KEY (idempotency_key, created_at)`. Depending on specific idempotency guarantees, organizations sometimes opt instead to use explicit active/archive tables rotated via a cron job, avoiding native DB partitioning to preserve strict unique keys.
```

---

## 8. Reconciliation & Ambiguous Downstream Outcomes

In a real UPI system, the downstream CBS (Core Banking System) or NPCI switch can return ambiguous outcomes: timeout, partial acknowledgement, or contradictory responses. This section defines how AgenticUPI handles those cases.

```
Scenario 1: HTTP timeout from simulated CBS endpoint
─────────────────────────────────────────────────────
  Our state: PROCESSING (debit applied to our ledger)
  CBS state: UNKNOWN (timeout — did it debit the real account?)

  Resolution:
  1. Mark transaction as SUCCESS in our system (debit is committed).
  2. Write outbox event: PAYMENT_CBS_TIMEOUT.
  3. Reconciliation job (runs every 15 min) queries CBS for
     transaction status by our txn_id.
  4a. CBS confirms debit → no action. States match.
  4b. CBS says no debit occurred → write REVERSAL entries:
      - REVERSAL_CREDIT to payer's account_balances
      - REVERSAL_CREDIT ledger entry
      - Update transaction status to REVERSED
      - Outbox event: PAYMENT_REVERSED

  Why debit-first, reverse-later (not hold-then-debit)?
  • In UPI, the liability model favors debiting first. A debit that
    doesn't reach the payee is reversed within T+1. A failed debit
    that was actually processed causes a double-debit — much worse.
  • Our system owns the payer's ledger. We can always reverse.
    We cannot guarantee the CBS will hold funds atomically.


Scenario 2: Out-of-order Kafka events
──────────────────────────────────────
  Events on the same partition (keyed by payer_vpa) arrive in order.
  But what if a MANDATE_REVOKED event races a PAYMENT_SUCCESS event?

  Resolution:
  • Each event carries a monotonic sequence number (the outbox row's
    auto-increment id, which is strictly ordered per DB commit).
  • Consumers track the last-processed sequence number per aggregate.
  • An event with sequence < last_processed is a late arrival → skip.
  • An event with sequence > last_processed + 1 is a gap → buffer
    until the missing event arrives (with a 30s timeout → alert).

  This is a standard out-of-order handling pattern. The key insight
  is that the outbox's auto-increment ID provides a total order
  within a single MariaDB instance — no need for a distributed
  sequence generator.


Scenario 3: Redis ↔ DB state mismatch after Redis restart
─────────────────────────────────────────────────────────
  Redis restarts and loses all cached mandates and velocity data.

  Impact:
  • Mandate cache: MISS → falls through to DB. Self-healing.
    No inconsistency — DB is the source of truth.
  • Velocity sorted set: LOST → the next request will start
    a fresh window.

  This means: after a Redis restart, agents can briefly exceed
  the daily limit (up to per_tx_limit per request until the
  window rebuilds organically, or up to daily_limit if many
  requests hit before the set re-populates).

  Mitigation:
  1. On application startup, warm velocity data from recent
     ledger_entries for all active mandates:
       SELECT mandate_umn, SUM(amount_paise), MAX(created_at)
       FROM ledger_entries le
       JOIN transactions t ON le.txn_id = t.txn_id
       WHERE le.created_at > NOW() - INTERVAL 24 HOUR
         AND le.entry_type = 'DEBIT'
         AND t.status = 'SUCCESS'
       GROUP BY mandate_umn;
     → Pre-populate velocity sorted sets in Redis.
  2. Belt-and-suspenders: the DB FOR UPDATE check prevents
     actual overdraft (balance going negative), even if
     velocity is temporarily inaccurate.
```

---

## 9. Security, Privacy & Secret Management

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        DATA CLASSIFICATION                                    │
├────────────────────────┬─────────────┬───────────────────────────────────────┤
│ Field                  │ Class       │ Handling                              │
├────────────────────────┼─────────────┼───────────────────────────────────────┤
│ user_vpa               │ PII         │ Logged as hash. Never in error msgs. │
│ agent_context          │ Untrusted   │ Stored for audit. Never parsed.       │
│ balance_paise          │ Sensitive   │ Never in API responses to agents.     │
│ secret_hash            │ Secret      │ Argon2id. Raw secret never stored.    │
│ txn_id / idempotency   │ Internal    │ Safe to log. Used for tracing.        │
│ amount_paise           │ Financial   │ Logged with txn_id for audit trail.   │
│ trace_id               │ Diagnostic  │ Ephemeral. Safe in external errors.   │
└────────────────────────┴─────────────┴───────────────────────────────────────┘

Secret Management:
──────────────────
• DB credentials: Kubernetes Secret (mounted as env vars), rotated via
  Vault dynamic secrets (120-day TTL, auto-rotation, no human access).
• Agent shared secrets: generated at registration (256-bit random),
  shown once to the registering user, stored as Argon2id hash.
  Rotation: user generates a new secret in the app → old secret is
  immediately invalidated → agent must update within 24h grace period.
• Redis AUTH password: Kubernetes Secret. TLS in transit.
• Kafka SASL credentials: Kubernetes Secret. TLS + SASL_SCRAM_SHA_512.

Encryption:
───────────
• At rest: MariaDB TDE (Transparent Data Encryption) with AES-256-CBC.
  Key managed by Vault Transit backend.
• In transit: TLS 1.3 everywhere (app↔DB, app↔Redis, app↔Kafka,
  gateway↔app). No plaintext internal communication.
• Backups: encrypted with a separate backup key in Vault.
  Backup access is audited.
```

---

## 10. Interview Signal

> **What Phase 2 demonstrates to a Navi/PhonePe interviewer:**

| Talking Point                                                | Why It Impresses                                                                                                      |
| ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| **All monetary values in paise (integer, never DECIMAL/FLOAT)** | Signals you've built real payment systems. Every interview candidate who writes `DECIMAL(10,2)` gets a follow-up about rounding errors in SUM aggregations. You won't. |
| **READ COMMITTED over REPEATABLE READ with explicit gap-lock reasoning** | This is a Staff+ question. Most candidates say "use default isolation." You can explain exactly why gap locks from the outbox relay would block payment inserts. |
| **`FOR UPDATE SKIP LOCKED` for outbox relay** | Shows you know MariaDB 10.6+ features and how to build concurrent consumers without deadlocks. |
| **No FK constraints — deliberate, with justification** | Counter-intuitive to junior engineers. You can explain the locking overhead FKs add at 10K TPS and why application-level enforcement within `@Transactional` is sufficient. |
| **`innodb_autoinc_lock_mode = 2` (interleaved)** | Deep InnoDB knowledge. Auto-increment lock modes are a classic PhonePe interview question for high-write-throughput tables. |
| **Signed BIGINT for balance (not UNSIGNED) to allow reconciliation corrections** | Shows you've dealt with real-world reconciliation edge cases where corrections must temporarily push balances negative. |
| **Velocity re-warming from ledger on Redis restart** | Shows you've thought about the blast radius of a cache failure and designed a recovery path that doesn't silently violate financial limits. |
| **Monthly partitioning by RANGE + `EXCHANGE PARTITION` for O(1) archival** | Production-grade data lifecycle. Interviewers at payment companies will probe your retention strategy — this shows you've operated tables with billions of rows. |

### Resume Bullet (Phase 2)

> Designed complete MariaDB persistence layer with double-entry ledger, transactional outbox, and `READ COMMITTED` isolation (eliminating gap-lock contention on concurrent outbox inserts). Integer-paise storage for zero-rounding financial operations. `SELECT FOR UPDATE SKIP LOCKED` for non-blocking outbox relay. Monthly range partitioning with `EXCHANGE PARTITION` for O(1) data archival.

---

*Phase 2 complete. Ready for Phase 3 (Low-Level Design — Backend Core & Concurrency) on your signal.*
