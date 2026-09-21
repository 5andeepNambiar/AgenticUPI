package com.agenticupi.repository;
import com.agenticupi.domain.AccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountBalance a WHERE a.userVpa = :vpa")
    Optional<AccountBalance> findByUserVpaForUpdate(@Param("vpa") String vpa);
}
