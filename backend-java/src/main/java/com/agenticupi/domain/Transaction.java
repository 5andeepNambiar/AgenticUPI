package com.agenticupi.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @Column(name = "txn_id")
    private String txnId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "payer_vpa")
    private String payerVpa;

    @Column(name = "payee_vpa")
    private String payeeVpa;

    @Column(name = "amount_paise")
    private long amountPaise;

    @Column(name = "status")
    private String status;

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getPayerVpa() { return payerVpa; }
    public void setPayerVpa(String payerVpa) { this.payerVpa = payerVpa; }
    public String getPayeeVpa() { return payeeVpa; }
    public void setPayeeVpa(String payeeVpa) { this.payeeVpa = payeeVpa; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}