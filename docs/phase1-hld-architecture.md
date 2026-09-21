# AgenticUPI — Phase 1: High-Level System Design (HLD) & Architecture

> **Autonomous, delegated low-value UPI execution engine** enabling untrusted AI agents to initiate micro-transactions (<₹500) without manual PIN prompts, while enforcing deterministic financial safety through user-defined revocable mandates, sliding-window velocity caps, cryptographic signature verification, zero dual-write data loss, and an atomic kill-switch.

> **Scope note:** This is a personal portfolio and interview-preparation project that simulates a UPI PSP-style architecture. It is not affiliated with Navi, NPCI, or any bank/PSP, and it does not claim to represent internal production systems. References to UPI-style flows are used to model realistic fintech constraints: mandates, ledgers, idempotency, reconciliation, and revocation.

---

## Table of Contents

1. [Functional Requirements](#1-functional-requirements)
2. [Non-Functional Requirements & SLA Targets](#2-non-functional-requirements--sla-targets)
3. [Scale Assumptions & Capacity Model](#3-scale-assumptions--capacity-model)
4. [System Architecture](#4-system-architecture)
   - [Architecture Diagram](#41-architecture-diagram)
   - [Request Lifecycle — Wall-Clock Budget Breakdown](#42-request-lifecycle--wall-clock-budget-breakdown)
   - [Component Interaction Sequence (Happy Path)](#43-component-interaction-sequence-happy-path)
   - [Transaction State Machine](#44-transaction-state-machine)
   - [Data Partitioning & Shard Ownership](#45-data-partitioning--shard-ownership)
5. [Trust Boundaries & Threat Model](#5-trust-boundaries--threat-model)
   - [Trust Boundary Map](#51-trust-boundary-map)
   - [STRIDE Analysis](#52-stride-analysis)
     - [T1: Prompt Injection](#t1-prompt-injection-spoofing--tampering)
     - [T2: Replay Attack](#t2-replay-attack-spoofing)
     - [T3: Man-in-the-Middle Payload Tampering](#t3-man-in-the-middle-payload-tampering)
     - [T4: Race-Condition Overdraft](#t4-race-condition-overdraft-elevation-of-privilege)
     - [T5: Denial of Service via Agent Flood](#t5-denial-of-service-via-agent-flood)
     - [T6: Information Disclosure via Error Messages](#t6-information-disclosure-via-error-messages)
6. [Failure Mode Analysis & Recovery](#6-failure-mode-analysis--recovery)
7. [Key Design Decisions & Trade-offs](#7-key-design-decisions--trade-offs)
8. [Reconciliation & Ambiguous Downstream Outcomes](#8-reconciliation--ambiguous-downstream-outcomes)
9. [Security, Privacy & Secret Management](#9-security-privacy--secret-management)

---

## 1. Functional Requirements

| ID   | Requirement                              | Detail                                                                                                                                                                                                                                                                         |
| ---- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| FR-1 | **Mandate Lifecycle Management**         | Users create, modify, pause, resume, and revoke delegated payment mandates via the app. Each mandate is scoped to a specific agent identity (API key hash), with per-transaction cap (≤₹500), daily rolling limit, merchant category whitelist, and TTL.                       |
| FR-2 | **Agent-Initiated Payment Execution**    | External AI agents submit structured payment intents (payer VPA, payee VPA, amount, category, context) via a signed REST API. The system validates the mandate, enforces velocity limits, debits the ledger, and returns a deterministic success/failure — no human PIN prompt. |
| FR-3 | **Cryptographic Agent Authentication**   | Every inbound request carries an HMAC-SHA256 signature over the canonical JSON payload. The gateway verifies it against a registered per-agent shared secret before any processing begins.                                                                                     |
| FR-4 | **24-Hour Sliding-Window Velocity Control** | A rolling 24-hour window (not calendar-day reset) tracks cumulative spend per mandate. Both per-transaction and daily-aggregate caps are enforced atomically in a single Redis Lua call — no TOCTOU gap.                                                                       |
| FR-5 | **Idempotent Exactly-Once Execution**    | Every payment intent carries a client-generated idempotency key. Duplicate submissions within a 48-hour TTL return the original result without re-debiting.                                                                                                                    |
| FR-6 | **Sub-5ms Mandate Revocation (Kill-Switch)** | User taps "Revoke" in-app → MariaDB mandate state is synchronously updated to `REVOKED` → Redis mandate cache is deleted and a short-lived `revoked:{mandate_umn}` tombstone is written. The DB is the durable source of truth; the Redis tombstone gives sub-5ms hot-path rejection for subsequent requests. |
| FR-7 | **Real-Time Audit Stream**               | Every mandate state change and transaction event is published to Kafka via a transactional outbox pattern, consumed by a WebSocket gateway, and pushed to the user's app in real time.                                                                                          |
| FR-8 | **Zero Double-Debit Guarantee**          | Under no failure scenario — application crash, DB failover, Kafka broker loss, Redis eviction — does the system debit a user's balance twice for the same logical payment.                                                                                                     |

---

## 2. Non-Functional Requirements & SLA Targets

| Dimension                  | Target                                  | Rationale                                                                                                                                        |
| -------------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| **P50 Latency (hot path)** | < 8ms                                   | Redis cache hit for mandate lookup + Lua velocity check. No DB round-trip for mandate resolution on valid warm requests; financial authorization still uses one ACID ledger transaction. |
| **P95 Latency (hot path)** | < 25ms                                  | Accounts for Redis → DB fallback on cold cache, GC pauses on G1/ZGC, and network jitter within the same AZ.                                     |
| **P99 Latency (full tx)**  | < 80ms                                  | Includes MariaDB `SELECT FOR UPDATE` + ledger write + outbox insert + commit. Absolute ceiling before the client gets a response.                |
| **Availability**           | 99.99% (≤52.6 min/year downtime)        | Active-passive DB failover with ProxySQL, Redis Sentinel (3-node), Kafka with ISR ≥ 2. No single point of failure.                               |
| **Peak TPS**               | 10,000 sustained, 15,000 burst (30s)    | Sized for user base growth curve. Horizontal scaling via stateless Spring Boot pods behind an L7 load balancer.                                   |
| **Daily Transaction Volume** | ~5–8 million micro-transactions        | Derived from 10K TPS sustained over ~14 peak hours + long-tail off-peak.                                                                        |
| **Idempotency Window**     | 48 hours                                | Redis TTL on idempotency keys. Covers retry storms, mobile network reconnects, and agent retry policies.                                         |
| **Kill-Switch Propagation**| < 5ms P99                               | Single synchronous `DEL` on Redis. No async fan-out needed — every request checks Redis before proceeding.                                       |
| **Data Durability**        | Zero loss                               | Transactional outbox (same DB commit as the ledger write). Kafka `acks=all`, `min.insync.replicas=2`.                                            |
| **RPO**                    | 0 for committed ledger writes within a shard | Requires synchronous or semi-synchronous replication for the ledger primary. Outbox guarantees committed events are not lost even if Kafka is temporarily down. |
| **RTO**                    | < 30 seconds                            | ProxySQL auto-failover to hot standby. Spring Boot pods are stateless — Kubernetes restarts in <5s.                                              |

---

## 3. Scale Assumptions & Capacity Model

```
┌─────────────────────────────────────────────────────────┐
│                    CAPACITY MODEL                       │
├─────────────────────────────────────────────────────────┤
│ Active mandates in steady state:     ~2 million         │
│ Avg mandate size in Redis:           ~512 bytes         │
│ Redis mandate cache footprint:       ~1 GB              │
│                                                         │
│ Avg transaction row size (MariaDB):  ~380 bytes         │
│ Daily ledger writes:                 ~8M rows           │
│ Daily ledger storage growth:         ~3 GB/day raw      │
│                                                         │
│ Kafka event throughput:              ~10K msg/s peak    │
│ Kafka avg message size:              ~1 KB              │
│ Kafka daily throughput:              ~8 GB/day          │
│                                                         │
│ Idempotency keys in Redis (48h TTL): ~16M keys          │
│ Idempotency Redis footprint:         ~2 GB              │
│ Total Redis memory (mandate+idemp):  ~4 GB (+ headroom) │
│                                                         │
│ Spring Boot pods (8-core, 4GB heap): 6–8 pods           │
│ MariaDB write IOPS estimate:         ~20K IOPS          │
│ Connection pool per pod:             50 connections      │
│ Total DB connections:                ~400 (with ProxySQL)│
└─────────────────────────────────────────────────────────┘
```

---

## 4. System Architecture

### 4.1 Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    AgenticUPI — System Architecture                              │
│                                                                                                  │
│  ┌─────────────────────┐                                                                         │
│  │   External AI Agent │  (ChatGPT, Autonomous Assistant, etc.)                                  │
│  │   ┌───────────────┐ │                                                                         │
│  │   │ FastAPI Signer│ │  Python 3.11 — Constructs structured intent, signs with HMAC-SHA256     │
│  │   └───────┬───────┘ │                                                                         │
│  └───────────┼─────────┘                                                                         │
│              │  POST /api/v1/agent/pay                                                           │
│              │  Headers: X-Agent-Id, X-Signature, X-Idempotency-Key, X-Timestamp                 │
│              ▼                                                                                   │
│  ╔═══════════════════════════════════════════════════════════════╗                                │
│  ║              INGESTION GATEWAY (L7 / Spring Cloud Gateway)   ║  ← TLS termination,           │
│  ║  ┌──────────┐  ┌──────────────┐  ┌────────────────────────┐  ║    rate limiting (token bucket)│
│  ║  │ TLS/mTLS │→ │ Rate Limiter │→ │ HMAC-SHA256 Verifier   │  ║    replay window = 300s       │
│  ║  │ Terminate │  │ (per agent)  │  │ (WebFilter, pre-route) │  ║                                │
│  ║  └──────────┘  └──────────────┘  └───────────┬────────────┘  ║                                │
│  ╚══════════════════════════════════════════════╪════════════════╝                                │
│              │                                  │                                                 │
│              │         TRUST BOUNDARY ══════════╪══════════════════════════                       │
│              │                                  │                                                 │
│              ▼                                  ▼                                                 │
│  ┌────────────────────────────────────────────────────────────────────────┐                       │
│  │                     SPRING BOOT POLICY ENGINE                         │                       │
│  │                     (Stateless, Horizontally Scaled)                   │                       │
│  │                                                                        │                       │
│  │  Request Flow (sequential, fail-fast):                                 │                       │
│  │                                                                        │                       │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │                       │
│  │  │ STEP 1: Idempotency Check                                      │   │                       │
│  │  │   Redis: GET idemp:{idempotency_key}                           │   │                       │
│  │  │   HIT → return cached response (no further processing)         │   │                       │
│  │  │   MISS → SET NX PX 172800000 (48h) with status=PROCESSING     │   │                       │
│  │  │   SET returned 0 (key exists) → concurrent duplicate, return   │   │                       │
│  │  └──────────────────────────┬──────────────────────────────────────┘   │                       │
│  │                             ▼                                          │                       │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │                       │
│  │  │ STEP 2: Mandate Resolution (Cache-Aside)                       │   │                       │
│  │  │   Redis: GET mandate:{agent_id}:{payer_vpa}                    │   │                       │
│  │  │   HIT → deserialize MandateDTO                                  │   │                       │
│  │  │   MISS → SELECT * FROM mandates WHERE agent_id=? AND vpa=?      │   │                       │
│  │  │          AND status='ACTIVE' AND valid_until > NOW()            │   │                       │
│  │  │          → SET mandate:{...} EX 300 (5-min TTL)                 │   │                       │
│  │  │   Validate: amount ≤ per_tx_limit, category ∈ allowed_cats     │   │                       │
│  │  └──────────────────────────┬──────────────────────────────────────┘   │                       │
│  │                             ▼                                          │                       │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │                       │
│  │  │ STEP 3: Velocity Guard (Atomic Redis Lua)                      │   │                       │
│  │  │   EVALSHA sliding_window_check                                  │   │                       │
│  │  │     KEY: velocity:{mandate_umn}                                 │   │                       │
│  │  │     ARGS: amount, daily_limit, now_ms, window_ms(86400000)     │   │                       │
│  │  │   Lua atomically:                                               │   │                       │
│  │  │     1. ZREMRANGEBYSCORE (evict entries older than window)       │   │                       │
│  │  │     2. Sum current window via ZRANGEBYSCORE                     │   │                       │
│  │  │     3. If (sum + amount) > daily_limit → REJECT                │   │                       │
│  │  │     4. Else ZADD pending reservation → ALLOW                   │   │                       │
│  │  │   Single round-trip, no TOCTOU race. Post-commit hook confirms  │   │                       │
│  │  │   the reservation; rollback compensates/removes it.             │   │                       │
│  │  └──────────────────────────┬──────────────────────────────────────┘   │                       │
│  │                             ▼                                          │                       │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │                       │
│  │  │ STEP 4: Atomic Ledger Operation (@Transactional, single ACID)  │   │                       │
│  │  │   BEGIN;                                                        │   │                       │
│  │  │     SELECT balance, version FROM account_balances               │   │                       │
│  │  │       WHERE vpa = :payer_vpa FOR UPDATE;                        │   │                       │
│  │  │     — Verify balance ≥ amount (insufficient funds → rollback)   │   │                       │
│  │  │     UPDATE account_balances SET balance = balance - :amount,     │   │                       │
│  │  │       version = version + 1 WHERE vpa = :payer_vpa              │   │                       │
│  │  │       AND version = :expected_version;                          │   │                       │
│  │  │     — rows_affected == 0 → optimistic lock violation → retry   │   │                       │
│  │  │     INSERT INTO ledger_entries (...);                           │   │                       │
│  │  │     INSERT INTO transactions (...);                             │   │                       │
│  │  │     INSERT INTO outbox_events (...);  ← SAME transaction       │   │                       │
│  │  │   COMMIT;                                                       │   │                       │
│  │  └──────────────────────────┬──────────────────────────────────────┘   │                       │
│  │                             ▼                                          │                       │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │                       │
│  │  │ STEP 5: Response + Idempotency Cache Update                    │   │                       │
│  │  │   Redis: SET idemp:{key} → serialized response (48h TTL)       │   │                       │
│  │  │   Return 200 OK { txn_id, status, amount, timestamp }          │   │                       │
│  │  └────────────────────────────────────────────────────────────────┘   │                       │
│  └────────────────────────────────────────────────────────────────────────┘                       │
│                                                                                                  │
│  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
│    BACKGROUND PROCESSES (Async, Eventually Consistent)                    │                       │
│  │                                                                                            │  │
│    ┌─────────────────────────────────────────────────────┐                 │                      │
│  │ │ OUTBOX RELAY (Scheduled @5s polling / Debezium CDC) │                                    │  │
│    │  SELECT * FROM outbox_events WHERE status='PENDING' │                 │                      │
│  │ │    ORDER BY created_at LIMIT 1000 FOR UPDATE        │                                    │  │
│    │    SKIP LOCKED; multiple workers poll every 100ms   │                 │                      │
│    │  → Produce to Kafka topic: upi.agentic.events       │                 │                      │
│  │ │  → UPDATE outbox_events SET status='PUBLISHED';     │                                    │  │
│    │  Kafka: acks=all, min.insync.replicas=2             │                 │                      │
│  │ └──────────────────────┬──────────────────────────────┘                                    │  │
│                           │                                                │                      │
│  │                        ▼                                                                   │  │
│    ┌──────────────────────────────────────┐    ┌──────────────────────┐     │                     │
│  │ │  Kafka Topic: upi.agentic.events    │───▶│ Consumer Group 1:    │                        │  │
│    │  Partitions: 12 (keyed by payer_vpa) │    │ Audit Logger /       │     │                     │
│  │ │  Retention: 7 days                  │    │ Compliance Store     │                        │  │
│    └──────────────────────┬──────────────┘    └──────────────────────┘     │                     │
│  │                        │                                                                   │  │
│                           │                   ┌──────────────────────┐     │                      │
│  │                        ├──────────────────▶│ Consumer Group 2:    │                        │  │
│                           │                   │ Notification Service │     │                      │
│  │                        │                   │ → Push / SMS / Email │                        │  │
│                           │                   └──────────────────────┘     │                      │
│  │                        │                                                                   │  │
│                           │                   ┌──────────────────────┐     │                      │
│  │                        └──────────────────▶│ Consumer Group 3:    │                        │  │
│                                               │ WebSocket Gateway    │     │                      │
│  │                                            │ → Live UI push to    │                        │  │
│                                               │   App (SockJS)       │     │                      │
│  │                                            └──────────┬───────────┘                        │  │
│   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┼ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘  │
│                                                          │                                       │
│                                                          ▼                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐                       │
│  │                         APP UI (Angular 16+ / Mobile)                  │                       │
│  │                                                                        │                       │
│  │  ┌───────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │                       │
│  │  │  Home Hero     │  │  Mandate Manager    │  │  Live Audit Feed   │  │                       │
│  │  │  Card          │  │  (CRUD + Kill       │  │  (WebSocket /      │  │                       │
│  │  │  (₹ spent /   │  │   Switch)            │  │   RxJS Observable) │  │                       │
│  │  │   daily limit) │  │                     │  │                     │  │                       │
│  │  └───────────────┘  └─────────────────────┘  └─────────────────────┘  │                       │
│  └────────────────────────────────────────────────────────────────────────┘                       │
│                                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                              INFRASTRUCTURE LAYER                                          │   │
│  │                                                                                            │   │
│  │  ┌─────────────┐  ┌───────────────────┐  ┌──────────────────┐  ┌────────────────────────┐ │   │
│  │  │  MariaDB     │  │  Redis Sentinel   │  │  Kafka Cluster   │  │  Observability Stack   │ │   │
│  │  │  (Primary +  │  │  (3-node, 1       │  │  (3 brokers,     │  │  Prometheus + Grafana  │ │   │
│  │  │   Hot Standby│  │   master + 2      │  │   ISR=2,         │  │  Jaeger (distributed   │ │   │
│  │  │   + ProxySQL)│  │   replicas)       │  │   12 partitions) │  │    tracing)            │ │   │
│  │  │  InnoDB,     │  │  maxmemory: 8GB   │  │  retention: 7d   │  │  ELK (structured logs) │ │   │
│  │  │  Semi-sync   │  │  noeviction /     │  │  segment: 1GB    │  │  PagerDuty (alerting)  │ │   │
│  │  │  GTID Repl.  │  │  isolated pools   │  │                  │  │                        │ │   │
│  │  └─────────────┘  └───────────────────┘  └──────────────────┘  └────────────────────────┘ │   │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Request Lifecycle — Wall-Clock Budget Breakdown

Every millisecond is accounted for. Budget for a successful warm-path payment:

```
Total P95 Budget: 25ms
═══════════════════════════════════════════════════════════════
│ Step                              │ P50   │ P95   │ Bound  │
├───────────────────────────────────┼───────┼───────┼────────┤
│ TLS + Gateway routing             │ 0.5ms │ 1ms   │ Infra  │
│ HMAC-SHA256 verification          │ 0.1ms │ 0.3ms │ CPU    │
│ Idempotency check (Redis GET)     │ 0.3ms │ 0.8ms │ Net    │
│ Idempotency SET NX PX             │ 0.3ms │ 0.8ms │ Net    │
│ Mandate lookup (Redis GET, warm)  │ 0.3ms │ 0.8ms │ Net    │
│ Velocity Lua EVALSHA              │ 0.5ms │ 1.5ms │ Redis  │
│ ─── DB Transaction ───            │       │       │        │
│   SELECT ... FOR UPDATE           │ 1.0ms │ 3.0ms │ InnoDB │
│   UPDATE account_balances         │ 0.5ms │ 2.0ms │ InnoDB │
│   INSERT ledger_entries           │ 0.5ms │ 1.5ms │ InnoDB │
│   INSERT transactions             │ 0.5ms │ 1.5ms │ InnoDB │
│   INSERT outbox_events            │ 0.3ms │ 1.0ms │ InnoDB │
│   COMMIT (fsync / group commit)   │ 1.0ms │ 5.0ms │ Disk   │
│ ─── End DB Transaction ───        │       │       │        │
│ Idempotency result cache SET      │ 0.3ms │ 0.8ms │ Net    │
│ Serialize + send HTTP response    │ 0.2ms │ 0.5ms │ CPU    │
├───────────────────────────────────┼───────┼───────┼────────┤
│ TOTAL                             │ 6.3ms │ 20.5ms│ ✅ <25 │
═══════════════════════════════════════════════════════════════

Cold path (mandate cache miss):       +3–5ms for DB round-trip → P95 ≈ 25ms
Absolute worst case (optimistic lock retry): +15ms → P99 ≈ 35ms
```

### 4.3 Component Interaction Sequence (Happy Path)

```
Agent           Gateway        PolicyEngine       Redis           MariaDB         Kafka
  │                │                │                │                │              │
  │──POST /pay────▶│                │                │                │              │
  │  +HMAC sig     │──verify sig──▶│                │                │              │
  │                │                │                │                │              │
  │                │                │──GET idemp:k──▶│                │              │
  │                │                │◀──MISS─────────│                │              │
  │                │                │──SET NX PX────▶│                │              │
  │                │                │◀──OK (1)───────│                │              │
  │                │                │                │                │              │
  │                │                │──GET mandate──▶│                │              │
  │                │                │◀──HIT──────────│                │              │
  │                │                │                │                │              │
  │                │                │──EVALSHA lua──▶│                │              │
  │                │                │◀──ALLOWED──────│                │              │
  │                │                │                │                │              │
  │                │                │──BEGIN─────────────────────────▶│              │
  │                │                │  SELECT FOR UPDATE             │              │
  │                │                │  UPDATE balance                │              │
  │                │                │  INSERT ledger                 │              │
  │                │                │  INSERT txn                    │              │
  │                │                │  INSERT outbox                 │              │
  │                │                │──COMMIT────────────────────────▶│              │
  │                │                │                │                │              │
  │                │                │──SET idemp:k──▶│                │              │
  │                │                │   (result,48h) │                │              │
  │                │                │                │                │              │
  │◀──200 OK───────│◀───response───│                │                │              │
  │                │                │                │                │              │
  │                │                │         [async, ~5s later]      │              │
  │                │                │     OutboxRelay────SELECT──────▶│              │
  │                │                │                │         outbox │              │
  │                │                │     OutboxRelay─────produce────────────────────▶│
  │                │                │     OutboxRelay────UPDATE──────▶│              │
  │                │                │                │   status=PUB   │              │
```

### 4.4 Transaction State Machine

The system separates **authorization**, **ledger reservation**, and **downstream settlement** so ambiguous external outcomes do not corrupt the internal ledger.

```
RECEIVED
  │
  ├── signature / schema / replay failure ───────────────▶ REJECTED_SECURITY
  │
  ├── mandate / limit / risk failure ────────────────────▶ REJECTED_POLICY
  │
  ▼
POLICY_APPROVED
  │
  ├── insufficient funds / DB conflict ──────────────────▶ FAILED_PRE_DEBIT
  │
  ▼
LEDGER_RESERVED
  │
  ├── downstream request accepted, final result unknown ─▶ DOWNSTREAM_PENDING
  │
  ├── downstream success ────────────────────────────────▶ SUCCESS
  │
  ├── downstream hard failure ───────────────────────────▶ REVERSED
  │
  └── timeout / ambiguous response ──────────────────────▶ RECONCILIATION_REQUIRED
```

**Key invariant:** once the system reaches `LEDGER_RESERVED`, any failure must be resolved through a compensating ledger entry, not by mutating or deleting the original ledger row. This preserves auditability and prevents hidden balance drift.

The `transactions` table stores:

```text
idempotency_key
request_hash
state
ledger_entry_id
downstream_reference_id
reconciliation_status
aggregate_version
```

Rules:

1. Same `idempotency_key` + same `request_hash` returns the original transaction result.
2. Same `idempotency_key` + different `request_hash` is rejected as `IDEMPOTENCY_CONFLICT`.
3. State transitions are monotonic and versioned; consumers ignore events older than their last processed `aggregate_version`.

### 4.5 Data Partitioning & Shard Ownership

A single MariaDB writer is not assumed to absorb all global traffic. The ledger is horizontally partitioned by a stable payer key:

```text
shard_id = hash(normalized_payer_vpa or user_id) % N
```

All write-path rows for one payer are colocated on the same shard:

```text
account_balances
ledger_entries
transactions
mandates
outbox_events
```

This preserves per-payer ordering and makes `SELECT ... FOR UPDATE` row locks local to one shard. It also prevents a cross-shard transaction from being required for the core debit/reservation path.

Routing rules:

| Concern | Design |
| ------- | ------ |
| Per-payer consistency | One payer maps to exactly one write shard. |
| Hot users | Queue naturally on the account row lock; per-agent rate limits prevent abuse. |
| Outbox relay | Runs per shard so each shard drains its own committed events. |
| Kafka key | `payer_vpa` or `user_id` to preserve user-visible event order. |
| Re-sharding | Add virtual buckets so shard migration can move bucket ranges without changing application semantics. |

For the personal project implementation, a single MariaDB instance is acceptable. The HLD, however, explicitly models the production scale path via payer-key sharding.

---

## 5. Trust Boundaries & Threat Model

### 5.1 Trust Boundary Map

```
╔═══════════════════════════════════════════════════════════════════════════╗
║  ZONE 0: UNTRUSTED (Public Internet)                                     ║
║  ┌─────────────────────────────────────────┐                             ║
║  │  External AI Agents                      │  ← Assume fully hostile.   ║
║  │  • Any LLM, any prompt, any behavior    │     No trust. No session   ║
║  │  • May be prompt-injected               │     state. Stateless auth  ║
║  │  • May replay captured requests         │     per request.           ║
║  │  • May attempt parameter tampering      │                            ║
║  └─────────────────────────┬───────────────┘                             ║
║                            │ TLS 1.3 only                                ║
╠════════════════════════════╪══════════════════════════════════════════════╣
║  ZONE 1: DMZ (Gateway)    │                                              ║
║  ┌─────────────────────────▼───────────────┐                             ║
║  │  Ingestion Gateway                       │  ← Validates identity.     ║
║  │  • TLS termination                      │     Rejects malformed.     ║
║  │  • Rate limiting (token bucket)         │     No business logic.     ║
║  │  • HMAC signature verification          │                            ║
║  │  • Timestamp freshness check (±300s)    │                            ║
║  │  • Request size limit (16KB)            │                            ║
║  └─────────────────────────┬───────────────┘                             ║
╠════════════════════════════╪══════════════════════════════════════════════╣
║  ZONE 2: TRUSTED (Internal Network)                                      ║
║  ┌─────────────────────────▼───────────────┐                             ║
║  │  Policy Engine + Ledger + Outbox        │  ← All business logic.     ║
║  │  • mTLS between services               │     DB credentials via     ║
║  │  • No direct external access            │     Vault / K8s secrets.   ║
║  └─────────────────────────────────────────┘                             ║
║                                                                           ║
║  ┌─────────────────────────────────────────┐                             ║
║  │  Redis / MariaDB / Kafka                │  ← No public endpoints.    ║
║  │  • Network-level isolation (VPC)        │     Auth required.         ║
║  │  • Encryption at rest (MariaDB TDE)     │     Audit logging.         ║
║  └─────────────────────────────────────────┘                             ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ZONE 3: USER DEVICE (App)                                               ║
║  ┌─────────────────────────────────────────┐                             ║
║  │  Angular UI / Mobile WebView            │  ← Authenticated via       ║
║  │  • Session token (HttpOnly, Secure)     │     login (OAuth2/OIDC).   ║
║  │  • WebSocket auth via ticket (one-time) │     CSRF protection.       ║
║  └─────────────────────────────────────────┘                             ║
╚══════════════════════════════════════════════════════════════════════════╝
```

### 5.2 STRIDE Analysis

#### T1: Prompt Injection (Spoofing / Tampering)

**Threat:** An attacker crafts a prompt that causes the AI agent to emit a payment intent with a tampered `payee_vpa` or inflated `amount`. The agent is untrusted — it may be running arbitrary user prompts.

**Mitigation — Defense in Depth (3 layers):**

```
Layer 1: Structural Schema Enforcement (FastAPI / Pydantic)
─────────────────────────────────────────────────────────────
• The agent's output is parsed through a strict Pydantic model.
• VPA format: regex-validated (e.g., ^[a-zA-Z0-9._-]+@[a-z]+$)
• Amount: constrained to Decimal with max=500.00, gt=0
• Category: enum-validated against a fixed set
• Any field that doesn't parse → 422, request dies before signing.
• Natural language "context" field is NEVER used in policy decisions.
  It is logged for audit only — treated as opaque, untrusted text.

Layer 2: Mandate-Scoped Constraints (Policy Engine)
─────────────────────────────────────────────────────
• Even if the agent emits a valid-looking intent, the policy engine
  checks: Is this payee_vpa in the mandate's allowed merchant list?
  Is the amount ≤ per_tx_limit? Is the category in allowed_categories?
• The mandate was created by the USER in the app, not the agent.
  The agent cannot modify mandate parameters.

Layer 3: Velocity Guard (Redis Lua)
─────────────────────────────────────
• Even if the agent rapidly fires valid intents, the 24-hour sliding
  window caps cumulative spend. No matter how creative the prompt
  injection, the financial blast radius is bounded by the user's
  own configured daily_limit.
```

**Principle:** The agent's output is treated as structured data, never as control flow. No field in the payment intent influences code paths, SQL queries, or cache keys through string interpolation. All identifiers are parameterized.

---

#### T2: Replay Attack (Spoofing)

**Threat:** An attacker intercepts a valid signed request and replays it to trigger duplicate payments.

**Mitigation — Three-barrier defense:**

```java
// In HMAC verification filter:
public class HmacVerificationFilter extends OncePerRequestFilter {

    private static final long MAX_CLOCK_SKEW_MS = 300_000; // ±5 minutes

    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String timestamp = request.getHeader("X-Timestamp");
        long requestEpochMs = Long.parseLong(timestamp);
        long now = Instant.now().toEpochMilli();

        // 1. Timestamp freshness — reject stale requests
        if (Math.abs(now - requestEpochMs) > MAX_CLOCK_SKEW_MS) {
            throw new ReplayAttackException(
                "Request timestamp outside acceptable window"
            );
        }

        // 2. HMAC covers timestamp — attacker cannot update timestamp
        //    without invalidating the signature
        String signatureBase = canonicalize(body, timestamp, idempotencyKey);
        // ... verify HMAC ...

        // 3. Idempotency key — even if replayed within the window,
        //    SET NX returns 0 (key already exists) → duplicate detected
        //    → return original result, no re-processing.
    }
}
```

1. **Timestamp window (±300s):** Requests older than 5 minutes are rejected outright.
2. **HMAC covers timestamp:** The attacker cannot forge a fresh timestamp without the secret.
3. **Idempotency key (48h TTL):** Even within the valid window, the same idempotency key returns the cached result.

---

#### T3: Man-in-the-Middle Payload Tampering

**Threat:** An intermediary modifies the request body (e.g., changes `amount` from ₹10 to ₹499) after the agent signs it but before the gateway receives it.

**Mitigation:**

```
HMAC-SHA256 signature is computed over the ENTIRE canonical payload:

  signature_base = canonical_json(body) + ":" + timestamp + ":" + idempotency_key

Any single byte change in any field → signature mismatch → 401 Unauthorized.

The canonical_json function:
  1. Sorts keys alphabetically (deterministic ordering)
  2. Removes whitespace (compact serialization)
  3. Uses consistent numeric formatting (no trailing zeros)

This ensures the same logical payload always produces the same signature
regardless of JSON serialization order differences between Python and Java.
```

Additionally: **TLS 1.3 is mandatory.** Plaintext HTTP is not accepted. The HMAC is a second layer — it defends against compromised TLS termination proxies or CDN misconfigurations, not as a replacement for transport security.

---

#### T4: Race-Condition Overdraft (Elevation of Privilege)

**Threat:** An agent fires 10 concurrent requests of ₹100 each against a mandate with a ₹500 daily limit. If velocity checks are non-atomic, all 10 might pass the check before any is recorded, resulting in ₹1000 spent.

> **This is the most critical threat in the system.**

Mitigated at two independent levels:

```
LEVEL 1: Redis Lua Atomic Velocity Check (Primary Guard)
═══════════════════════════════════════════════════════════
The Lua script is a single atomic operation in Redis — no interleaving.

  EVALSHA sliding_window_velocity_check 1
    velocity:{mandate_umn}         -- KEY
    {txn_id}                       -- unique member
    {amount_paise}                 -- amount in paise (integer)
    {daily_limit_paise}            -- daily limit in paise
    {now_ms}                       -- current unix timestamp ms
    {window_ms}                    -- 86400000 (24 hours)

  Lua logic (atomic):
    1. ZREMRANGEBYSCORE key -inf (now_ms - window_ms)  -- evict expired
    2. local members = ZRANGEBYSCORE key (now_ms - window_ms) +inf
    3. local sum = 0; for each member: sum += tonumber(amount from member)
    4. if (sum + amount) > daily_limit then return {0, sum}  -- REJECT
    5. ZADD key now_ms pending:{txn_id}:{amount_paise}
    6. EXPIRE key window_ms/1000
    7. return {1, sum + amount, reservation_id}  -- ALLOW

  Key insight: Steps 2–5 execute atomically. Even with 10 concurrent
  requests, Redis processes Lua scripts sequentially. Request #6 will
  see the amounts from requests #1–5 already in the sorted set.

  Reservation finalization:
    • If the MariaDB transaction commits, a post-commit hook converts
      pending:{txn_id}:{amount} → committed:{txn_id}:{amount}.
    • If the MariaDB transaction rolls back, the service removes the
      pending member as compensation.
    • If the app crashes after Redis reservation but before DB commit,
      pending reservations expire after a short TTL and are ignored by
      the Lua summation once they are older than the pending-grace window.
    • Committed members remain for the full rolling 24-hour window.


LEVEL 2: Database Pessimistic Lock (Secondary Guard / Belt-and-Suspenders)
══════════════════════════════════════════════════════════════════════════
  SELECT balance, version FROM account_balances
    WHERE vpa = :payer_vpa FOR UPDATE;

  -- InnoDB row lock held until COMMIT.
  -- Even if Redis fails open (network partition), the DB prevents
  -- overdraft because concurrent transactions queue on the row lock.
  -- Only one transaction can hold the lock at a time.

  -- Additionally, optimistic version check:
  UPDATE account_balances
    SET balance = balance - :amount, version = version + 1
    WHERE vpa = :payer_vpa AND version = :expected_version;
  -- rows_affected == 0 → stale read → retry or fail
```

**Why both?** Redis Lua is the fast path — it rejects violating requests in <1ms without touching the DB, saving InnoDB lock contention. The DB `FOR UPDATE` lock is the safety net — if Redis experiences a network partition or data loss (even with Sentinel, split-brain is possible), the DB independently prevents double-debit. Redis is allowed to be conservative and temporarily reduce available mandate quota; MariaDB remains the correctness anchor for money movement. Neither layer alone is sufficient; together, they provide the zero-overdraft guarantee.

---

#### T5: Denial of Service via Agent Flood

**Threat:** A compromised or misbehaving agent sends millions of requests per second, overwhelming the system.

**Mitigation (layered):**

```
Layer 1: L7 Rate Limiter (Gateway)
─────────────────────────────────────
  • Per-agent-id token bucket: 100 req/s sustained, 200 burst
  • Global: 15,000 req/s (system capacity ceiling)
  • Implemented via Spring Cloud Gateway's RequestRateLimiter
    filter backed by Redis (standard sliding window counter)

Layer 2: Connection Limits
─────────────────────────────────────
  • Max concurrent connections per agent IP: 50
  • HTTP/2 max concurrent streams: 100
  • Request body size limit: 16KB

Layer 3: Agent Suspension
─────────────────────────────────────
  • If an agent exceeds rate limits 3x in 60 seconds,
    its agent_id is added to a Redis blacklist with 15-minute TTL.
  • All subsequent requests return 429 without HMAC verification
    (saves CPU on signature computation).

Layer 4: Circuit Breaker (Resilience4j)
─────────────────────────────────────
  • If DB latency exceeds P99 threshold (100ms), the circuit opens.
  • All incoming requests receive 503 Service Unavailable.
  • Half-open probe every 10 seconds.
  • Prevents cascading failure from overwhelming the DB connection pool.
```

---

#### T6: Information Disclosure via Error Messages

**Threat:** Stack traces, SQL errors, or internal identifiers leak to the agent in error responses.

**Mitigation:**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Agent-facing errors: generic, no internals
    @ExceptionHandler(MandateNotFoundException.class)
    public ResponseEntity<AgentErrorResponse> handleMandateNotFound(
            MandateNotFoundException ex) {
        // Internal log: detailed
        log.warn("Mandate not found: {}", ex.getMandateUmn());
        return ResponseEntity.status(403).body(
            new AgentErrorResponse(
                "MANDATE_INVALID",
                "No active mandate found for this request"
            )
            // ← no UMN, no VPA, no SQL, no stack trace
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentErrorResponse> handleUnexpected(Exception ex) {
        String traceId = MDC.get("traceId"); // Jaeger trace ID
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return ResponseEntity.status(500).body(
            new AgentErrorResponse(
                "INTERNAL_ERROR",
                "Request failed. Reference: " + traceId
            )
            // ← agent gets trace ID for support, nothing else
        );
    }
}
```

---

## 6. Failure Mode Analysis & Recovery

| Failure Scenario                     | Detection                                        | Impact                                                                                                                                  | Recovery                                                                                                                                                               |
| ------------------------------------ | ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Redis master down**                | Sentinel failover (auto, <15s)                   | Mandate lookups fall through to DB (slower, still correct). Velocity checks: circuit breaker opens → requests rejected with 503 (safe). | Sentinel promotes replica. Cache warms organically. No data loss — Redis is a cache, not source of truth for balances.                                                 |
| **MariaDB primary down**             | ProxySQL health check (1s intervals)             | All writes fail. Inflight transactions roll back. No partial commits (InnoDB guarantees).                                               | ProxySQL routes to hot standby. Semi-sync/GTID replication is configured for zero or near-zero committed-write loss within a shard. Outbox relay resumes from the last committed position. |
| **Kafka broker down (1 of 3)**       | ISR shrinks. `min.insync.replicas=2` still met.  | No impact — producer continues writing.                                                                                                 | Replace broker. Topic partitions rebalance.                                                                                                                            |
| **Kafka cluster down (all brokers)** | Producer `delivery.timeout.ms` expires.          | Outbox events accumulate in MariaDB (status=PENDING). Transactions continue normally — Kafka is async. Audit stream delayed.            | Kafka recovers → outbox relay catches up. Events ordered by `created_at`. Consumer idempotency (by event_id) handles duplicates from at-least-once relay.              |
| **App pod crash mid-transaction**    | DB transaction uncommitted → auto-rollback.      | The specific request fails. Balance unchanged. Idempotency key in Redis is in PROCESSING state.                                         | Agent retries with same idempotency key → SET NX fails → but value is PROCESSING → system deletes stale PROCESSING key (age > 30s) → retry proceeds.                  |
| **Network partition (app ↔ Redis)**  | Lettuce client timeout (100ms).                  | Mandate cache misses → DB fallback. Velocity check unreachable → fail-closed (reject). Idempotency check unreachable → fail-closed.     | Partition heals → normal operation resumes. Conservative fail-closed posture means no financial risk during partition.                                                  |

---

## 7. Key Design Decisions & Trade-offs

| Decision                                      | Choice                                           | Alternative Considered                      | Why This Choice                                                                                                                                                                                                                                                             |
| --------------------------------------------- | ------------------------------------------------ | ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Velocity data structure**                   | Redis Sorted Set + Lua + pending reservations    | Redis counter with TTL (simpler)            | A simple counter resets at a fixed time boundary (midnight). A sorted set gives a true sliding window — no gaming by timing transactions around the reset. Pending reservations are confirmed after DB commit or removed after rollback/crash expiry.                      |
| **Outbox pattern vs. dual write**             | Transactional Outbox                             | Direct Kafka produce in same code path      | Dual write (DB commit + Kafka produce) is not atomic. If Kafka fails after DB commit, the event is lost. With outbox, the event is in the same DB transaction as the ledger entry — guaranteed to exist if the debit exists.                                                 |
| **Idempotency in Redis vs. DB**               | Redis acceleration + DB unique constraint        | Redis-only idempotency                      | Redis returns duplicate responses quickly, but MariaDB is the durable correctness layer. `transactions.idempotency_key` is unique and stores `request_hash`; same key/hash returns the original result, while same key/different hash is rejected as tampering/conflict. |
| **Fail-open vs. fail-closed on Redis failure** | **Fail-closed** (reject requests)                | Fail-open (skip velocity check, proceed)    | For a financial system, fail-open is unacceptable — it would allow unbounded spend when Redis is down. Fail-closed means some valid requests are rejected during outage, but no overdraft is possible. The DB `FOR UPDATE` is the last line of defense.                      |
| **Pessimistic vs. optimistic locking**         | **Both** (`FOR UPDATE` + version column)          | Optimistic only (retry on conflict)         | At high concurrency on the same VPA, optimistic-only causes excessive retries (thundering herd on hot accounts). `FOR UPDATE` serializes access; the version column catches edge cases where the lock is released between read and write (e.g., connection pool timeout).     |
| **Outbox relay: Polling vs. CDC**              | High-frequency polling (initial), Debezium CDC (future) | Debezium from day one                       | Debezium adds operational complexity (connector management, schema registry, Kafka Connect cluster). Initial relay uses multiple workers, `SELECT ... LIMIT 1000 FOR UPDATE SKIP LOCKED`, and 100-250ms polling per shard. Migrate to CDC when scale demands it. |
| **Kafka partitioning key**                     | `payer_vpa`                                      | `mandate_umn` or random                     | Partitioning by `payer_vpa` guarantees all events for a single user arrive at the same partition → same consumer → ordered. Critical for audit feed — user must see transactions in order.                                                                                   |

---

## 8. Reconciliation & Ambiguous Downstream Outcomes

AgenticUPI must handle the classic payments problem: the internal ledger may reserve funds, then the downstream PSP/CBS/NPCI-style simulator may timeout before returning a final result. A timeout is not a failure; it is an unknown.

**Design rule:** never guess final payment state from an HTTP timeout.

| Scenario | Internal State | Recovery Action |
| -------- | -------------- | --------------- |
| Downstream returns success | `SUCCESS` | Mark transaction successful; publish success event. |
| Downstream returns deterministic failure | `REVERSED` | Write compensating credit ledger entry; publish reversal event. |
| Downstream times out | `RECONCILIATION_REQUIRED` | Keep reservation, enqueue reconciliation job, query downstream by reference ID. |
| Downstream returns duplicate reference | Existing terminal state | Return stored idempotent result; do not create new ledger entry. |
| Reconciliation exceeds SLA | `MANUAL_REVIEW_REQUIRED` | Freeze transaction state and surface to operations dashboard. |

Reconciliation jobs are idempotent and keyed by `transaction_id`. They compare:

```text
internal transaction state
ledger reservation state
downstream reference status
outbox publication status
consumer acknowledgement/audit state
```

If a mismatch is found, the reconciler fixes state through a new event or compensating ledger entry. It never edits historical ledger rows in place.

---

## 9. Security, Privacy & Secret Management

Financial systems leak risk through logs, metrics, and secrets as often as through business logic. This project treats those surfaces explicitly.

| Area | Control |
| ---- | ------- |
| Agent secrets | Stored encrypted at rest; exposed only through a secret manager abstraction. Support key rotation with `active_secret_version` and `previous_secret_version`. |
| HMAC verification | Use constant-time signature comparison. HMAC covers canonical body, timestamp, idempotency key, and agent ID. |
| VPA / PII handling | Store normalized VPA; redact or hash VPA in logs. UI may display full VPA only to the authenticated user. |
| Audit logs | Append-only, trace-correlated, and redacted. Never log HMAC secrets, raw tokens, or full request bodies containing sensitive context. |
| Transport | TLS 1.3 externally; mTLS between internal services where practical. |
| Access control | Mandate management APIs require user authentication; increasing limits requires step-up authentication. |
| Abuse response | Per-agent suspension, per-user kill switch, global platform kill switch, and risk-triggered manual approval. |

Optional risk-engine hooks before ledger authorization:

```text
new payee
unusual amount for user
high failure rate from agent
category outside historical pattern
rapid repeated attempts below the cap
agent reputation downgrade
```

The risk engine can return `ALLOW`, `DENY`, or `STEP_UP_REQUIRED`. For an autonomous no-PIN payment product, `STEP_UP_REQUIRED` should stop agent execution and ask the user to approve manually.

---

## Interview Signal: What This Phase Demonstrates

> **For your resume and interview walkthrough — these are the highest-signal talking points from Phase 1:**

| Talking Point                                                                                                | Why It Impresses                                                                                                   |
| ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| Dual-guard concurrency control (Redis Lua + DB `FOR UPDATE`)                                                 | Shows you understand neither layer alone is sufficient in a distributed system and can articulate exactly why.       |
| Fail-closed semantics with explicit justification                                                            | Separates you from candidates who default to fail-open without considering financial blast radius.                  |
| Wall-clock budget with per-hop P50/P95 breakdown                                                             | Proves you've operated systems at this latency tier, not just designed them on a whiteboard.                        |
| Transactional outbox over dual-write, with a pragmatic "polling now, CDC later" migration path               | Shows you balance correctness against operational complexity — exactly what a Staff+ engineer does.                 |
| STRIDE threat model with defense-in-depth layers (not just "we use HTTPS")                                   | Demonstrates security thinking that goes beyond checkbox compliance.                                               |
| Sliding window via sorted set (not midnight-reset counter)                                                   | A subtle but real-world detail that interviewers at payment companies will specifically probe.                      |

### Resume Bullet (suggested)

> **AgenticUPI — Autonomous UPI Micro-Payment Engine** *(Personal Project / System Design)*
> Designed a production-inspired delegated payment execution system enabling AI agents to initiate UPI-style micro-transactions (<₹500) under user-defined mandates. Specified atomic sliding-window velocity control (Redis Lua + InnoDB pessimistic locking), durable idempotency, transactional outbox streaming, fail-closed fault tolerance, and sub-5ms cache-level revocation enforcement for a 10K TPS target architecture.
> *Stack: Java 21, Spring Boot 3, MariaDB, Redis, Kafka, Python/FastAPI, Angular 16*

---

*Phase 1 complete. Ready for Phase 2 (Data Models, Persistence & Isolation Strategy) on your signal.*
