package com.agenticupi.repository;
import com.agenticupi.domain.Mandate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MandateRepository extends JpaRepository<Mandate, String> {

    List<Mandate> findByUserVpa(String userVpa);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Mandate m WHERE m.agentId = :agentId AND m.userVpa = :vpa")
    Optional<Mandate> findByAgentIdAndUserVpaForUpdate(@Param("agentId") String agentId, @Param("vpa") String vpa);
}
