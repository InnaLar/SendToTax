package com.example.demo.services;

import com.example.demo.clients.TaxClient;
import com.example.demo.model.entity.Receipt;
import com.example.demo.model.entity.Shedlock;
import com.example.demo.model.entity.ShedlockStatus;
import com.example.demo.repository.ReceiptRepository;
import com.example.demo.repository.ShedlockRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@AllArgsConstructor
public class SendReceiptJob {

    private final ReceiptRepository repository;
    private final ShedlockRepository shedlockRepository;
    private final TaxClient taxClient;

    @Scheduled(cron = "*/10 * * * * *")
    public void processReceipt() {
        try {
            log.info("Start receipts' processing");
            if (!lock("processReceipt")) {

                List<Receipt> receipts = repository.findAllByProcessedFalse(20);
                for (Receipt receipt : receipts) {
                    try {
                        taxClient.sendReceipt(receipt.getId(), receipt.getSum());
                        receipt.setProcessed(true);
                        repository.save(receipt);
                    } catch (Exception e) {
                        log.info("Request tax-service failed", e);
                    }
                }

                log.info("{} receipts processed", receipts.size());
            } else {
                log.info("method is being done");
            }
        } finally {
            unlock("processReceipt");
        }
    }

    private void unlock(String name) {
        Optional<Shedlock> byName = shedlockRepository.findByName(name);
        if (byName.isEmpty()) {
            return;
        }
        Shedlock shedlock = byName.get();
        shedlock.setStatus(ShedlockStatus.READY_TO_WORK);
        shedlockRepository.save(shedlock);
    }

    @Transactional
    public boolean lock(String name) {

        Optional<Shedlock> byName = shedlockRepository.findByName(name);
        if (byName.isEmpty()) {
            return false;
        }

        Shedlock shedlock = byName.get();
        if (shedlock.getStatus().equals(ShedlockStatus.IN_PROCESS)) {
            return false;
        } else {
            shedlock.setStatus(ShedlockStatus.IN_PROCESS);
            return true;
        }

    }


}
