package com.agenticupi.config;

import com.agenticupi.domain.AgentCredential;
import com.agenticupi.exception.AgentErrorCode;
import com.agenticupi.repository.AgentCredentialRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

@Component
public class HmacAuthenticationFilter extends OncePerRequestFilter {

    private static final long MAX_CLOCK_SKEW_MS = 300_000; // 5 minutes
    
    // Using a setter injects to avoid circular dependencies in Spring Security filter chains
    private AgentCredentialRepository agentCredentialRepository;

    public HmacAuthenticationFilter(AgentCredentialRepository agentCredentialRepository) {
        this.agentCredentialRepository = agentCredentialRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/v1/agent")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        byte[] bodyBytes = wrappedRequest.getInputStream().readAllBytes();

        String agentId = request.getHeader("X-Agent-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String idempotencyKey = request.getHeader("X-Idempotency-Key");
        String providedSignature = request.getHeader("X-Signature");

        if (agentId == null || providedSignature == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Headers");
            return;
        }

        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - Long.parseLong(timestamp)) > MAX_CLOCK_SKEW_MS) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, AgentErrorCode.REPLAY_WINDOW_EXCEEDED.name());
            return;
        }

        // Fetch Real DB Credential
        Optional<AgentCredential> optCreds = agentCredentialRepository.findByAgentId(agentId);
        if (optCreds.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Agent ID");
            return;
        }

        // In Production: rawSecret = kms.decrypt(optCreds.get().getSecretCiphertext())
        // For local runtime, we use the raw DB string to avoid AWS API calls.
        byte[] rawSecret = optCreds.get().getSecretCiphertext().getBytes();

        String bodyHash = DigestUtils.sha256Hex(bodyBytes);
        String signatureBase = String.format("%s.%s.%s.%s", bodyHash, timestamp, idempotencyKey, agentId);

        String expectedSignature = new HmacUtils("HmacSHA256", rawSecret).hmacHex(signatureBase);

        if (!MessageDigest.isEqual(expectedSignature.getBytes(), providedSignature.getBytes())) {
            Arrays.fill(rawSecret, (byte) 0);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, AgentErrorCode.INVALID_SIGNATURE.name());
            return;
        }

        Arrays.fill(rawSecret, (byte) 0);
        filterChain.doFilter(wrappedRequest, response);
    }
}
