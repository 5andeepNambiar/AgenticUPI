package com.agenticupi.service;

import com.agenticupi.domain.OutboxEvent;
import com.agenticupi.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {

    private final OutboxEventRepository outboxEventRepository;
    private final String workerId = UUID.randomUUID().toString();

    public OutboxClaimService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimEventsForPublishing(int limit) {
        // Find rows securely without waiting for other workers
        List<OutboxEvent> batch = outboxEventRepository.findPendingEventsForUpdate(limit);

        // Immediately mutate status so other workers ignore them.
        // Lock is released when this method commits.
        for (OutboxEvent event : batch) {
            event.setStatus("IN_PROGRESS");
            event.setLockedBy(workerId);
        }

        outboxEventRepository.saveAll(batch);
        return batch;
    }
}
