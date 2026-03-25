package com.example.demo.services;

import com.example.demo.model.entity.Receipt;
import com.example.demo.repository.ReceiptRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class SendReceiptJob {

    private final ReceiptRepository repository;

    @Scheduled(cron = "*/10 * * * * *")
    @Transactional
    public void processReceipt() {
        log.info("Start receipts' processing");
        List<Receipt> receipts = repository.findby20Unprocessed();
        for (Receipt receipt : receipts) {
            receipt.setProcessed(true);
            repository.save(receipt);
        }
        log.info("{} receipts processed", receipts.size());
    }
}
