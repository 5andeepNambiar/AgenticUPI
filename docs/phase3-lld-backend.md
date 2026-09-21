# AgenticUPI — Phase 3: Low-Level Design (LLD) & Backend Core (Java/Spring Boot)

> **Core backend implementation** detailing the exact Java/Spring Boot mechanics to achieve 10K TPS with zero double-debits. This phase covers raw-payload HMAC authentication, two-phase distributed velocity reservations, `SKIP LOCKED` outbox relays, and fail-closed mandate kill-switches.

---

## 1. Error Taxonomy & Observability

Before writing business logic, we define a strict taxonomy. Every failure must map to a specific machine-readable code. Untrusted agents rely on these codes to branch logic; we rely on them for Grafana alerting.

```java
public enum AgentErrorCode {
    INVALID_SIGNATURE(401),
    REPLAY_WINDOW_EXCEEDED(401),
    MANDATE_REVOKED(403),
    MANDATE_EXPIRED(403),
    MANDATE_PAUSED(403),
    POLICY_VIOLATION(403),       // E.g., merchant category not allowed
    VELOCITY_EXCEEDED(429),      // Hit daily or per-txn limit
    INSUFFICIENT_BALANCE(400),
    IDEMPOTENCY_CONFLICT(409),   // Same idempotency_key, different request_hash
    DUPLICATE_REQUEST(409),      // Handled internally by returning 200 with old response
    INTERNAL_ERROR(500);

    private final int httpStatus;
    // constructor, getters
}
```

**MDC (Mapped Diagnostic Context) Tracing:**
Every request gets a `trace_id` injected at the gateway. This flows through SLF4J, is written to the `transactions` table, and is returned to the agent on 500 errors.

---

## 2. HMAC-SHA256 Request Authentication (Raw Payload)

**Problem:** JSON canonicalization across languages (Python `json.dumps` vs Java Jackson) is brittle. Object key order or float formatting differences cause signature mismatches.
**Solution:** The signature base is the *exact raw bytes* of the HTTP request body.

```java
@Component
public class HmacAuthenticationFilter extends OncePerRequestFilter {
    
    private static final long MAX_CLOCK_SKEW_MS = 300_000; // 5 minutes
    private final AgentCredentialsRepository agentRepo;
    private final KmsDecryptor kmsDecryptor;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        
        // 1. We must read the raw body bytes strictly before parsing as JSON.
        // Reading it explicitly here and passing it downstream prevents Empty/Consumed body issues.
        byte[] bodyBytes = wrappedRequest.getInputStream().readAllBytes();
        
        String agentId = request.getHeader("X-Agent-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String idempotencyKey = request.getHeader("X-Idempotency-Key");
        String providedSignature = request.getHeader("X-Signature");

        // 2. Replay Window Validation
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - Long.parseLong(timestamp)) > MAX_CLOCK_SKEW_MS) {
            throw new AgentAuthException(AgentErrorCode.REPLAY_WINDOW_EXCEEDED);
        }

        // 3. Load and decrypt secret (Optimized with Caffeine cache in production to avoid KMS latency)
        AgentCredential creds = credentialCache.get(agentId); 
        byte[] rawSecret = kmsDecryptor.decrypt(creds.getSecretCiphertext(), creds.getSecretKmsKeyId());

        // 4. Construct Signature Base (SHA256 of Raw Body + Headers)
        String bodyHash = DigestUtils.sha256Hex(bodyBytes);
        String signatureBase = String.format("%s.%s.%s.%s",
                bodyHash,
                timestamp,
                idempotencyKey,
                agentId
        );

        // 5. Verify HMAC
        String expectedSignature = HmacUtils.hmacSha256Hex(rawSecret, signatureBase);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(), providedSignature.getBytes())) {
            throw new AgentAuthException(AgentErrorCode.INVALID_SIGNATURE);
        }

        // Wipe decrypted secret from memory
        Arrays.fill(rawSecret, (byte) 0);

        filterChain.doFilter(wrappedRequest, response);
    }
}
```

---

## 3. Two-Phased Redis Velocity Guard & Idempotency

### The Problem with Caching Before Committing
If a Lua script increments the user's velocity usage, and subsequently the DB `FOR UPDATE` lock times out causing a rollback, the user permanently loses that velocity quota for a transaction that never happened.

**Solution:** Redis provides *acceleration*, but the Database is the *authority*. 
1. Redis Lua creates a PENDING reservation.
2. The `@Transactional` DB method commits the ledger.
3. A `TransactionSynchronization` post-commit hook finalizes or clears the Redis reservation.

### Component 1: The Lua Script (Two-Phase)

```lua
-- keys[1] = velocity set, keys[2] = pending set
-- args[1] = txn_id, args[2] = amount_paise, args[3] = daily limit, args[4] = now_ms, args[5] = window_ms

local velocityKey = KEYS[1]
local pendingKey = KEYS[2]

local now_ms = tonumber(ARGV[4])
local window_ms = tonumber(ARGV[5])
local amount_paise = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local txn_id = ARGV[1]

-- 1. Clean up old window instances by timestamp
-- Score is timestamp_ms, member is "txn_id:amount_paise"
redis.call('ZREMRANGEBYSCORE', velocityKey, '-inf', now_ms - window_ms)
redis.call('ZREMRANGEBYSCORE', pendingKey, '-inf', now_ms - window_ms) -- drop super stale pending

-- 2. Calculate current usage (committed + pending)
local members = redis.call('ZRANGE', velocityKey, 0, -1)
local pending = redis.call('ZRANGE', pendingKey, 0, -1)
local sum = 0

-- Parse amount from member string "txn_id:amount"
for _, member in ipairs(members) do 
    local _, _, amount_str = string.find(member, ".*:(%d+)")
    if amount_str then sum = sum + tonumber(amount_str) end
end
for _, member in ipairs(pending) do 
    local _, _, amount_str = string.find(member, ".*:(%d+)")
    if amount_str then sum = sum + tonumber(amount_str) end
end

-- 3. Check limit
if (sum + amount_paise) > limit then
    return 0 -- REJECT VELOCITY EXCEEDED
end

-- 4. Create PENDING reservation
-- member = txn_id:amount_paise, score = now_ms
local member_val = txn_id .. ":" .. tostring(amount_paise)
redis.call('ZADD', pendingKey, now_ms, member_val)
redis.call('EXPIRE', velocityKey, math.ceil(window_ms/1000))
-- Only expire the entire pending key if we want to drop all pending reservations if the app crashes hard
-- Better: let ZREMRANGEBYSCORE clean up old pending ones organically, and app explicitly removes on rollback.

return 1 -- ALLOWED (Reservation held)
```

### Component 2: Idempotency & Request Hash Filter

If an agent resends the same idempotency key with a *different* amount, it's a silent data corruption risk. We hash the payload.

```java
@Service
public class IdempotencyService {
    
    // Checks Redis first. If MISS, returns true (continue).
    // If HIT with matching hash, throws DuplicateRequestException (handled at controller to return 200 cached).
    // If HIT with mismatched hash, throws IdempotencyConflictException (returns 409).
    public void claimOrThrow(String idempKey, String bodyHash) {
        String redisKey = "idemp:" + idempKey;
        
        Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, bodyHash + ":PROCESSING", 48, TimeUnit.HOURS);
        
        if (Boolean.FALSE.equals(isNew)) {
            String existingVal = stringRedisTemplate.opsForValue().get(redisKey);
            if (existingVal == null) {
                 // Race condition: key expired between setIfAbsent and get. Retry boundary.
                 throw new TransientDataAccessException("Idempotency key expired during resolution");
            }

            // Split stored value: "hash:STATUS:result_payload"
            String[] parts = existingVal.split(":", 3);
            String storedHash = parts[0];
            String storedStatus = parts[1];

            if (!storedHash.equals(bodyHash)) {
                // Same idempotency key, different request content -> Potential malicious replay or client bug
                throw new IdempotencyConflictException(AgentErrorCode.IDEMPOTENCY_CONFLICT);
            }

            if ("PROCESSING".equals(storedStatus)) {
                // Concurrent request for same key is in-flight. 
                // Return 409 or 202 to tell the agent to back off and poll.
                throw new DuplicateRequestException(AgentErrorCode.DUPLICATE_REQUEST, "Transaction is currently processing. Retry in 2s.");
            } else if ("COMPLETED".equals(storedStatus)) {
                // Return the cached final response (parts[2]) cleanly
                throw new CachedResponseException(parts[2]);
            }
        }
    }
}
```

---

## 4. Core Payment Switch (ACID Boundary)

This is the absolute heart of the system. Notice that the Database handles the *final* idempotency guard via `DuplicateKeyException` — Redis can be wiped, and the DB will still protect the user.

```java
@Service
public class PaymentSwitchService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResult processPayment(PaymentIntent intent) {
        
        // 0. DB Idempotency & DB Mandate Revalidation (Pre-Locking)
        // Check DB for duplicate to avoid catching DataIntegrityViolationException post-mutation.
        if (txnRepo.existsByIdempotencyKey(intent.getIdempotencyKey())) {
            // Deduplication logic offloaded to REQUIRES_NEW lookup
            return handleDuplicateRecovery(intent); 
        }

        // Re-read Mandate strictly from DB (authority) before debit to avoid Cache-DB revocation race
        Mandate mandate = mandateRepo.findByAgentIdAndUserVpaForUpdate(intent.getAgentId(), intent.getPayerVpa())
            .orElseThrow(() -> new PaymentException(AgentErrorCode.MANDATE_REVOKED));
        if (mandate.getStatus() != MandateStatus.ACTIVE) {
            throw new PaymentException(AgentErrorCode.MANDATE_REVOKED);
        }

        // 1. PESSIMISTIC LOCK: Serialize balance mutations for this VPA
        AccountBalance balance = balanceRepo.findByUserVpaForUpdate(intent.getPayerVpa())
            .orElseThrow(() -> new AccountNotFoundException());

        if (balance.getBalancePaise() < intent.getAmountPaise()) {
            throw new PaymentException(AgentErrorCode.INSUFFICIENT_BALANCE);
        }

        // 2. OPTIMISTIC MUTATION
        int updated = balanceRepo.deductBalance(
            intent.getPayerVpa(), 
            intent.getAmountPaise(), 
            balance.getVersion()
        );
        if (updated == 0) throw new OptimisticLockingFailureException("Balance version skew");

        try {
            // 3. FINAL IDEMPOTENCY GUARD (Database UNIQUE constraint on idempotency_key)
            Transaction txn = createTransactionRecord(intent, mandate, "LEDGER_RESERVED");
            txnRepo.saveAndFlush(txn); // flush triggers constraint check
            
            // 4. DOUBLE-ENTRY LEDGER 
            createLedgerEntry(txn.getTxnId(), intent.getPayerVpa(), "DEBIT", intent.getAmountPaise());
            createLedgerEntry(txn.getTxnId(), "UPI_OUTBOUND_SUSPENSE", "CREDIT", intent.getAmountPaise());
            
            // 5. TRANSACTIONAL OUTBOX
            outboxRepo.save(createOutboxEvent("PAYMENT_RESERVED", txn.getTxnId(), intent.getPayerVpa()));
            
            // 6. POST-COMMIT HOOKS
            registerPostCommitHooks(txn.getTxnId(), intent, mandate);
            
            return new TransactionResult(txn.getTxnId(), "LEDGER_RESERVED");
            
        } catch (DataIntegrityViolationException e) {
            // Redis evicted the key, but DB caught the duplicate idempotency_key!
            Transaction existing = txnRepo.findByIdempotencyKey(intent.getIdempotencyKey());
            if (!existing.getRequestHash().equals(intent.getRequestHash())) {
                throw new PaymentException(AgentErrorCode.IDEMPOTENCY_CONFLICT);
            }
            throw new DuplicateRequestException(existing);
        }
    }

    private void registerPostCommitHooks(String txnId, PaymentIntent intent, Mandate mandate) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // DB Commit Success: Move velocity from PENDING to COMMITTED in Redis
                velocityService.finalizeReservation(mandate.getUmn(), txnId, intent.getAmountPaise());
                idempotencyService.cacheFinalResponse(intent.getIdempotencyKey(), intent.getRequestHash(), "SUCCESS");
            }
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    // DB Failed/Insufficient Funds: Clear the pending quota reservation
                    velocityService.clearReservation(mandate.getUmn(), txnId);
                    idempotencyService.deleteProcessingKey(intent.getIdempotencyKey());
                }
            }
        });
    }
}
```

---

## 5. Mandate Resolution & Kill-Switch

The kill-switch must execute synchronously against the DB to ensure durable safety, and leverage Redis tombstones for <5ms hot-path rejection.

```java
@Service
public class MandateManagementService {

    @Transactional
    public void revokeMandate(String umn) {
        // 1. DURABLE DB UPDATE (Authority)
        int updated = mandateRepo.updateStatusToRevoked(umn);
        if (updated == 0) throw new MandateNotFoundException();

        // 2. CACHE TOMBSTONE (Acceleration)
        // Even if Redis is down, we don't fail here. The DB is already updated.
        // If Redis is down, subsequent payments Miss cache -> hit DB -> see REVOKED.
        try {
            // Delete cache by UMN and write Revoked Tombstone by UMN for consistency
            redisTemplate.delete("mandate_umn:" + umn); 
            redisTemplate.opsForValue().set("revoked_umn:" + umn, "1", 60, TimeUnit.SECONDS); 
        } catch (RedisConnectionFailureException e) {
            log.warn("Failed to set revocation tombstone for {}. DB is safe, fail-closed semantics apply.", umn);
        }
    }
}

@Service
public class MandateResolutionService {
    
    public Mandate resolveOrThrow(String agentId, String vpa, String umn) {
        // 1. Fast Tombstone Check (<1ms)
        if (redisTemplate.hasKey("revoked_umn:" + umn)) {
            throw new PaymentException(AgentErrorCode.MANDATE_REVOKED);
        }

        // 2. Cache Resolution
        String cacheKey = "mandate_umn:" + umn;
        Mandate m = (Mandate) redisTemplate.opsForValue().get(cacheKey);
        
        if (m == null) {
            // 3. Fallback to DB (Authority)
            m = mandateRepo.findByUmn(umn)
                    .orElseThrow(() -> new PaymentException(AgentErrorCode.MANDATE_REVOKED));
            
            if(m.getStatus() == MandateStatus.REVOKED || m.getStatus() == MandateStatus.EXPIRED) {
                 throw new PaymentException(AgentErrorCode.MANDATE_REVOKED);
            }

            // Only populate cache for ACTIVE/PAUSED mandates
            redisTemplate.opsForValue().set(cacheKey, m, 5, TimeUnit.MINUTES);
        }

        if (m.getStatus() == MandateStatus.PAUSED) {
            throw new PaymentException(AgentErrorCode.MANDATE_PAUSED);
        }
        
        return m;
    }
}
```

---

## 6. High-Throughput Transactional Outbox Relay (Claim-Publish-Ack)

Holding a DB transaction open (`FOR UPDATE`) while waiting on Kafka I/O is a severe antipattern at 10K TPS. The relay uses a two-transaction "Claim-Publish-Ack" pattern scattered across multiple workers.

```java
@Component
public class OutboxRelayWorker {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxService outboxService;
    private final String workerId = UUID.randomUUID().toString();

    // Runs frequently (e.g. every 100ms) on multiple pods.
    @Scheduled(fixedDelayString = "100") 
    public void relayBatch() {
        
        // Transaction 1: Claim rows quickly and COMMIT.
        // FOR UPDATE SKIP LOCKED is used inside claimPendingBatch.
        // Marks status = 'IN_PROGRESS' and sets locked_by = workerId.
        List<OutboxEvent> batch = outboxService.claimPendingBatch(5000, workerId);

        if (batch.isEmpty()) return;

        // Publish to Kafka OUTSIDE any database transaction.
        // DB connections are released back to the pool.
        List<String> successfulEventIds = new ArrayList<>();
        List<String> failedEventIds = new ArrayList<>();

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send("upi.agentic.events", event.getPartitionKey(), event.getPayload())
                             .get(500, TimeUnit.MILLISECONDS); 
                successfulEventIds.add(event.getEventId());
            } catch (Exception e) {
                failedEventIds.add(event.getEventId());
            }
        }
        
        // Transaction 2: Ack results
        if (!successfulEventIds.isEmpty()) {
            outboxService.markPublished(successfulEventIds);
        }
        if (!failedEventIds.isEmpty()) {
            outboxService.incrementRetryOrFail(failedEventIds);
        }
    }
}
```

---

## 7. Interview Signal (Phase 3)

> **What this phase proves to a hiring manager:**

| Talking Point | Why It Impresses |
|--------------|------------------|
| **DB is Authority, Redis is Acceleration** | Real distributed systems fail. Proving that your cache logic is explicitly designed to handle cache desync, eviction, and failure without compromising financial integrity is a Staff-level requirement. |
| **Two-Phase Velocity Commits via `TransactionSynchronization`** | Demonstrates deep understanding of failure edge cases. Without this, a DB rollback results in permanent quota loss for the user. |
| **Post-Commit vs Context Issues** | Realizing that connection pool timeouts or lock timeouts impact the DB but not Redis, requiring programmatic hooks to bind cache state to the actual DB commit outcome. |
| **Raw Payload HMAC** | Calling out the nightmare of multi-language JSON canonicalization. Experienced engineers use raw byte hashing. |
| **Multi-Worker `SKIP LOCKED` Outbox** | Moving past naive `@Scheduled` tutorials. Proving you know how to scale database polling horizontally across multiple pods without row contention or duplicate processing. |

### Resume Bullet (Phase 3 Backend Core)
> Designing/Implementing core JVM payment switch targeted to handle 10,000 TPS with zero data loss. Engineered 2-phase distributed velocity guard ensuring caching layers (Redis Lua) synchronize durably with MariaDB via Spring post-commit hooks. Prevented JSON canonicalization vulnerabilities using raw-byte HMAC verification, and designed horizontally scaled Kafka outbox relays utilizing `FOR UPDATE SKIP LOCKED` and Claim-Publish-Ack patterns.

---
*Phase 3 complete. Ready for Phase 4 (Untrusted AI Agent Service - Python/FastAPI).*