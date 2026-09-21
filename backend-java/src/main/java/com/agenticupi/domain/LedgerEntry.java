package com.agenticupi.domain;
import jakarta.persistence.*;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long id;
    
    @Column(name = "txn_id")
    private String txnId;
    
    @Column(name = "account_id")
    private String accountId;
    
    @Column(name = "direction")
    private String direction;
    
    @Column(name = "amount_paise")
    private long amountPaise;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
}
