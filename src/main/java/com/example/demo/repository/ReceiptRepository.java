package com.example.demo.repository;

import com.example.demo.model.entity.Receipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                select r from Receipt r
                where r.processed = false limit 20
            """)
    List<Receipt> findby20Unprocessed();
}
