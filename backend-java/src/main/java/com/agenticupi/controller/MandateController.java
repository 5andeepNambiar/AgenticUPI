package com.agenticupi.controller;

import com.agenticupi.domain.Mandate;
import com.agenticupi.repository.AgentCredentialRepository;
import com.agenticupi.repository.MandateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user/mandates")
public class MandateController {

    private final MandateRepository mandateRepository;
    private final AgentCredentialRepository agentCredentialRepository;

    public MandateController(MandateRepository mandateRepository, AgentCredentialRepository agentCredentialRepository) {
        this.mandateRepository = mandateRepository;
        this.agentCredentialRepository = agentCredentialRepository;
    }

    // In a real application, the User's VPA would come from an established session
    // token (like a JWT), not a header or hardcoded value.
    // For this POC, we use a basic query param or header to mock user auth.
    @GetMapping
    public ResponseEntity<List<Mandate>> getUserMandates(@RequestHeader(value = "X-User-Vpa", defaultValue = "user@upi") String userVpa) {
        List<Mandate> userMandates = mandateRepository.findByUserVpa(userVpa);
        userMandates.forEach(m -> {
            agentCredentialRepository.findById(m.getAgentId())
                    .ifPresent(agent -> m.setAgentName(agent.getAgentName()));
            // Fake daily consumption for UI demo logic. Real impl would sum LedgerEntry amounts for today.
            m.setConsumedTodayPaise(0);
        });
        return ResponseEntity.ok(userMandates);
    }

    @DeleteMapping("/{umn}")
    public ResponseEntity<Void> revokeMandate(@PathVariable String umn) {
        // Hard-deleting is usually bad, so we perform a soft-delete / status update
        mandateRepository.findById(umn).ifPresent(mandate -> {
            mandate.setStatus("REVOKED");
            mandateRepository.save(mandate);
        });
        return ResponseEntity.ok().build();
    }
}
