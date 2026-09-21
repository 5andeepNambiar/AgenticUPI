package com.agenticupi.domain;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_credentials")
public class AgentCredential {
    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "secret_ciphertext")
    private String secretCiphertext;

    @Column(name = "secret_kms_key_id")
    private String secretKmsKeyId;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "status")
    private String status;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(String secretCiphertext) { this.secretCiphertext = secretCiphertext; }
    public String getSecretKmsKeyId() { return secretKmsKeyId; }
    public void setSecretKmsKeyId(String secretKmsKeyId) { this.secretKmsKeyId = secretKmsKeyId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
