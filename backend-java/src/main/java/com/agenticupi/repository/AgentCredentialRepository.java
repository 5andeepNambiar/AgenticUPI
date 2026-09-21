package com.agenticupi.repository;
import com.agenticupi.domain.AgentCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AgentCredentialRepository extends JpaRepository<AgentCredential, String> {
    Optional<AgentCredential> findByAgentId(String agentId);
}
