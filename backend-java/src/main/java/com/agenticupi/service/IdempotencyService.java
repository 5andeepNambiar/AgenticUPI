package com.agenticupi.service;

import com.agenticupi.exception.AgentErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import java.util.concurrent.TimeUnit;

@Service
public class IdempotencyService {

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotencyService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // Checks Redis first. If MISS, returns true (continue).
    // If HIT with matching hash, throws DuplicateRequestException (handled at controller to return 200 cached).
    // If HIT with mismatched hash, throws IdempotencyConflictException (returns 409).
    public void claimOrThrow(String idempKey, String bodyHash) {
        String redisKey = "idemp:" + idempKey;

        Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, bodyHash + ":PROCESSING", 48, TimeUnit.HOURS);

        if (Boolean.FALSE.equals(isNew)) {
            String existingVal = stringRedisTemplate.opsForValue().get(redisKey);
            if (existingVal == null) {
                 // Race condition: key expired between setIfAbsent and get. Retry boundary.
                 throw new TransientDataAccessResourceException("Idempotency key expired during resolution");
            }

            // Split stored value: "hash:STATUS:result_payload"
            String[] parts = existingVal.split(":", 3);
            String storedHash = parts[0];
            String storedStatus = parts[1];

            if (!storedHash.equals(bodyHash)) {
                // Same idempotency key, different request content -> Potential malicious replay or client bug
                throw new RuntimeException(AgentErrorCode.IDEMPOTENCY_CONFLICT.name());
            }

            if ("PROCESSING".equals(storedStatus)) {
                // Concurrent request for same key is in-flight.
                // Return 409 or 202 to tell the agent to back off and poll.
                throw new RuntimeException(AgentErrorCode.DUPLICATE_REQUEST.name() + " - Transaction is currently processing. Retry in 2s.");
            } else if ("COMPLETED".equals(storedStatus)) {
                // Return the cached final response (parts[2]) cleanly
                throw new RuntimeException("CACHED:" + parts[2]);
            }
        }
    }

    public void cacheFinalResponse(String idempKey, String bodyHash, String responsePayload) {
        String redisKey = "idemp:" + idempKey;
        stringRedisTemplate.opsForValue().set(redisKey, bodyHash + ":COMPLETED:" + responsePayload, 48, TimeUnit.HOURS);
    }

    public void deleteProcessingKey(String idempKey) {
        stringRedisTemplate.delete("idemp:" + idempKey);
    }
}
