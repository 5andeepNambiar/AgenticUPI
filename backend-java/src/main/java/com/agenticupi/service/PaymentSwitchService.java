package com.agenticupi.service;

import com.agenticupi.domain.*;
import com.agenticupi.dto.PaymentIntent;
import com.agenticupi.exception.AgentErrorCode;
import com.agenticupi.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class PaymentSwitchService {

    private final IdempotencyService idempotencyService;
    private final TransactionRepository txnRepo;
    private final MandateRepository mandateRepo;
    private final AccountBalanceRepository balanceRepo;
    private final OutboxEventRepository outboxRepo;
    private final LedgerEntryRepository ledgerRepo;

    public PaymentSwitchService(IdempotencyService idempotencyService,
                                TransactionRepository txnRepo,
                                MandateRepository mandateRepo,
                                AccountBalanceRepository balanceRepo,
                                OutboxEventRepository outboxRepo,
                                LedgerEntryRepository ledgerRepo) {
        this.idempotencyService = idempotencyService;
        this.txnRepo = txnRepo;
        this.mandateRepo = mandateRepo;
        this.balanceRepo = balanceRepo;
        this.outboxRepo = outboxRepo;
        this.ledgerRepo = ledgerRepo;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String processPayment(PaymentIntent intent) {

        // 0. DB Idempotency & DB Mandate Revalidation (Pre-Locking)
        if (txnRepo.existsByIdempotencyKey(intent.getIdempotencyKey())) {
            throw new RuntimeException(AgentErrorCode.DUPLICATE_REQUEST.name());
        }

        Mandate mandate = mandateRepo.findByAgentIdAndUserVpaForUpdate(intent.getAgentId(), intent.getPayerVpa())
                .orElseThrow(() -> new RuntimeException(AgentErrorCode.MANDATE_REVOKED.name()));

        if (!"ACTIVE".equals(mandate.getStatus())) {
            throw new RuntimeException(AgentErrorCode.MANDATE_REVOKED.name());
        }

        // 1. PESSIMISTIC LOCK: Serialize balance mutations for this VPA
        AccountBalance balance = balanceRepo.findByUserVpaForUpdate(intent.getPayerVpa())
                .orElseThrow(() -> new RuntimeException(AgentErrorCode.INSUFFICIENT_BALANCE.name()));

        if (balance.getBalancePaise() < intent.getAmountPaise()) {
            throw new RuntimeException(AgentErrorCode.INSUFFICIENT_BALANCE.name());
        }

        // 2. OPTIMISTIC MUTATION
        balance.setBalancePaise(balance.getBalancePaise() - intent.getAmountPaise());
        try {
            balanceRepo.save(balance); // Relies on @Version for optimistic locking checks
        } catch (OptimisticLockingFailureException e) {
            throw new RuntimeException("Balance version skew. Retry required.");
        }

        String txnId = "txn_" + UUID.randomUUID().toString();

        try {
            // 3. FINAL IDEMPOTENCY GUARD (Database UNIQUE constraint will throw DataIntegrityViolationException on duplicate)
            Transaction txn = new Transaction();
            txn.setTxnId(txnId);
            txn.setIdempotencyKey(intent.getIdempotencyKey());
            txn.setRequestHash(intent.getRequestHash());
            txn.setAgentId(intent.getAgentId());
            txn.setPayerVpa(intent.getPayerVpa());
            txn.setPayeeVpa(intent.getPayeeVpa());
            txn.setAmountPaise(intent.getAmountPaise());
            txn.setStatus("LEDGER_RESERVED");
            txnRepo.saveAndFlush(txn);

            // 4. DOUBLE-ENTRY LEDGER
            LedgerEntry debit = new LedgerEntry();
            debit.setTxnId(txnId);
            debit.setAccountId(intent.getPayerVpa());
            debit.setDirection("DEBIT");
            debit.setAmountPaise(intent.getAmountPaise());
            ledgerRepo.save(debit);

            LedgerEntry suspense = new LedgerEntry();
            suspense.setTxnId(txnId);
            suspense.setAccountId("UPI_OUTBOUND_SUSPENSE");
            suspense.setDirection("CREDIT");
            suspense.setAmountPaise(intent.getAmountPaise());
            ledgerRepo.save(suspense);

            // 5. TRANSACTIONAL OUTBOX
            OutboxEvent event = new OutboxEvent();
            event.setEventType("PAYMENT_SUCCESS");
            event.setAggregateType("TRANSACTION");
            event.setAggregateId(txnId);
            event.setPayload("{\"txnId\":\"" + txnId + "\",\"vpa\":\"" + intent.getPayerVpa() + "\"}");
            event.setStatus("PENDING");
            outboxRepo.save(event);

            // 6. POST-COMMIT HOOKS (Ensures Cache stays in sync with DB commit)
            registerPostCommitHooks(txnId, intent.getIdempotencyKey(), intent.getRequestHash(), intent.getAmountPaise(), mandate.getUmn());

            return txnId;

        } catch (DataIntegrityViolationException e) {
            // DB caught the duplicate idempotency_key!
            throw new RuntimeException(AgentErrorCode.IDEMPOTENCY_CONFLICT.name());
        }
    }

    private void registerPostCommitHooks(String txnId, String idempKey, String requestHash, long amountPaise, String umn) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // DB Success: Cache final response for 48 hrs in redis
                idempotencyService.cacheFinalResponse(idempKey, requestHash, "SUCCESS:" + txnId);
            }
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    // DB Rolled Back: Remove idempotency processing lock
                    idempotencyService.deleteProcessingKey(idempKey);
                }
            }
        });
    }
}
