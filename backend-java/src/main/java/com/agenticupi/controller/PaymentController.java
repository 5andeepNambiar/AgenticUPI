package com.agenticupi.controller;

import com.agenticupi.dto.PaymentIntent;
import com.agenticupi.service.IdempotencyService;
import com.agenticupi.service.PaymentSwitchService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/v1/agent")
public class PaymentController {

    private final PaymentSwitchService paymentSwitchService;
    private final IdempotencyService idempotencyService;

    public PaymentController(PaymentSwitchService paymentSwitchService, IdempotencyService idempotencyService) {
        this.paymentSwitchService = paymentSwitchService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/execute-payment")
    public ResponseEntity<Map<String, String>> executePayment(
            @RequestBody PaymentIntent intent,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Agent-Id") String agentId,
            HttpServletRequest request) {

        // The request is wrapped by HmacAuthenticationFilter
        ContentCachingRequestWrapper wrappedRequest = org.springframework.web.util.WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        String requestHash;
        if (wrappedRequest != null) {
            requestHash = DigestUtils.sha256Hex(wrappedRequest.getContentAsByteArray());
        } else {
            // Fallback, though we shouldn't hit this if filter is active
            requestHash = String.valueOf(intent.hashCode());
        }

        intent.setIdempotencyKey(idempotencyKey);
        intent.setAgentId(agentId);
        intent.setRequestHash(requestHash);

        try {
            // 1. Redis Cache 2-Phase Idempotency: Claim Velocity Lock
            idempotencyService.claimOrThrow(idempotencyKey, requestHash);

            // 2. Process Core ACID Payment
            String txnId = paymentSwitchService.processPayment(intent);

            return ResponseEntity.ok(Map.of("txn_id", txnId, "status", "LEDGER_RESERVED"));

        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("CACHED:")) {
                // Return cached final response unconditionally
                String cachedTxn = msg.split(":")[2];
                return ResponseEntity.ok(Map.of("txn_id", cachedTxn, "status", "LEDGER_RESERVED"));
            }
            throw e;
        }
    }
}
