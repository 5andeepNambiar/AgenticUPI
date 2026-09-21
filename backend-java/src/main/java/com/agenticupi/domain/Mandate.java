package com.agenticupi.domain;
import jakarta.persistence.*;

@Entity
@Table(name = "mandates")
public class Mandate {
    @Id
    @Column(name = "umn")
    private String umn;
    
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "user_vpa")
    private String userVpa;

    @Column(name = "status")
    private String status;

    @Column(name = "daily_limit_paise")
    private long dailyLimitPaise;

    @Transient
    private String agentName;

    @Transient
    private long consumedTodayPaise;

    public String getUmn() { return umn; }
    public void setUmn(String umn) { this.umn = umn; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getUserVpa() { return userVpa; }
    public void setUserVpa(String userVpa) { this.userVpa = userVpa; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getDailyLimitPaise() { return dailyLimitPaise; }
    public void setDailyLimitPaise(long dailyLimitPaise) { this.dailyLimitPaise = dailyLimitPaise; }
    public String getAgentName() { return agentName != null ? agentName : agentId; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public long getConsumedTodayPaise() { return consumedTodayPaise; }
    public void setConsumedTodayPaise(long consumedTodayPaise) { this.consumedTodayPaise = consumedTodayPaise; }
}
