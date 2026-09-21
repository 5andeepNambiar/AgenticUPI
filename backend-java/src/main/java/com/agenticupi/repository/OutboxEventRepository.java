package com.agenticupi.repository;
import com.agenticupi.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    // MariaDB SKIP LOCKED implementation to pull events securely and rapidly
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingEventsForUpdate(@Param("limit") int limit);
}
