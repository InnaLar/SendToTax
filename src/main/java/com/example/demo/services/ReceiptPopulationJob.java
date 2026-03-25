package com.example.demo.services;

import com.example.demo.model.entity.Receipt;
import com.example.demo.model.entity.ReceiptSource;
import com.example.demo.repository.ReceiptRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@AllArgsConstructor
public class ReceiptPopulationJob {

    private final ReceiptRepository repository;

    @Scheduled(cron = "*/10 * * * * *")
    public void processJob() {
        log.info("Start receipts populating");
        List<Receipt> receipts = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < 10; i++) {
            Receipt receipt = Receipt.builder()
                    .processed(false)
                    .sum(String.valueOf(random.nextDouble(1000)))
                    .source(ReceiptSource.REFUND)
                    .build();
            receipts.add(receipt);
        }
        repository.saveAll(receipts);
        log.info("{} receipts added", 10);
    }
}
