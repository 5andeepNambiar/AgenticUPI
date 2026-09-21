package com.agenticupi.repository;
import com.agenticupi.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    boolean existsByIdempotencyKey(String idempotencyKey);
}
