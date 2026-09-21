package com.agenticupi.domain;
import jakarta.persistence.*;

@Entity
@Table(name = "account_balances")
public class AccountBalance {
    @Id
    @Column(name = "user_vpa")
    private String userVpa;

    @Column(name = "balance_paise")
    private long balancePaise;

    @Version
    @Column(name = "version")
    private long version;

    public String getUserVpa() { return userVpa; }
    public void setUserVpa(String userVpa) { this.userVpa = userVpa; }
    public long getBalancePaise() { return balancePaise; }
    public void setBalancePaise(long balancePaise) { this.balancePaise = balancePaise; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
