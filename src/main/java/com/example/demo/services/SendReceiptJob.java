package com.example.demo.services;

import com.example.demo.clients.TaxClient;
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
    private final TaxClient taxClient;

    @Transactional
    @Scheduled(cron = "*/10 * * * * *")
    public void processReceipt() {
        log.info("Start receipts' processing");

        List<Receipt> receipts = repository.findAllByProcessedFalse(20);
        for (Receipt receipt : receipts) {
            taxClient.sendReceipt(receipt.getId(), receipt.getSum());
            receipt.setProcessed(true);
            repository.save(receipt);
        }

        log.info("{} receipts processed", receipts.size());
    }


}
