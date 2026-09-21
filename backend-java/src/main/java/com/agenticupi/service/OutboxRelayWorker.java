package com.agenticupi.service;

import com.agenticupi.domain.OutboxEvent;
import com.agenticupi.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxClaimService outboxClaimService;

    public OutboxRelayWorker(KafkaTemplate<String, String> kafkaTemplate,
                             OutboxEventRepository outboxEventRepository,
                             OutboxClaimService outboxClaimService) {
        this.kafkaTemplate = kafkaTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxClaimService = outboxClaimService;
    }

    @Scheduled(fixedDelayString = "100")
    public void relayBatch() {
        // High-throughput Claim-Publish-Ack pattern.

        // 1. Transaction 1: Claim rows quickly (using SKIP LOCKED -> IN_PROGRESS)
        List<OutboxEvent> batch = outboxClaimService.claimEventsForPublishing(50);

        if (batch.isEmpty()) return;

        List<OutboxEvent> successfulEvents = new ArrayList<>();
        List<OutboxEvent> failedEvents = new ArrayList<>();

        // 2. Publish to Kafka OUTSIDE any database transaction.
        // The pessimistic db locks are strictly limited to the duration of step 1.
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send("upi.agentic.events", event.getAggregateId(), event.getPayload())
                        .get(500, TimeUnit.MILLISECONDS);
                successfulEvents.add(event);
            } catch (Exception e) {
                log.error("Failed to publish event {}", event.getEventId(), e);
                failedEvents.add(event);
            }
        }

        // 3. Transaction 2: Ack results
        for (OutboxEvent event : successfulEvents) {
            event.setStatus("PUBLISHED");
            outboxEventRepository.save(event);
        }
        for (OutboxEvent event : failedEvents) {
            event.setStatus("FAILED");
            // Retry mechanisms omitted for brevity, but would increment a retry_count here
            outboxEventRepository.save(event);
        }
    }
}
