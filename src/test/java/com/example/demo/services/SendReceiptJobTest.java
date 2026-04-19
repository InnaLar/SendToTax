package com.example.demo.services;

import com.example.demo.clients.TaxClient;
import com.example.demo.model.entity.Receipt;
import com.example.demo.model.entity.Shedlock;
import com.example.demo.model.entity.ShedlockStatus;
import com.example.demo.repository.ReceiptRepository;
import com.example.demo.repository.ShedlockRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SendReceiptJobTest {

    // -----------------------------------------------------------------------
    // Тест 1: Лок уже занят — никакие чеки не должны отправляться в налоговую.
    // Документирует базовое намерение: если lock() == true → работа не делается.
    // ПРОХОДИТ при любом состоянии бага в LockService.
    // -----------------------------------------------------------------------
    @Test
    void whenLockAlreadyHeld_noReceiptsSentToTax() {
        LockService lockService = mock(LockService.class);
        when(lockService.lock(anyString())).thenReturn(true); // лок занят

        ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
        ShedlockRepository shedlockRepository = mock(ShedlockRepository.class);
        TaxClient taxClient = mock(TaxClient.class);

        when(shedlockRepository.findByName(anyString())).thenReturn(Optional.of(
                Shedlock.builder().status(ShedlockStatus.IN_PROCESS).build()
        ));

        SendReceiptJob job = new SendReceiptJob(receiptRepository, shedlockRepository, taxClient, lockService);
        job.processReceipt();

        verify(taxClient, never()).sendReceipt(any(), any());
        verify(receiptRepository, never()).findAllByProcessedFalse(anyInt());
    }

    // -----------------------------------------------------------------------
    // Тест 2: Лок свободен — все непроверенные чеки должны уйти в налоговую.
    // Документирует базовое намерение: если lock() == false → обрабатываем чеки.
    // ПРОХОДИТ при любом состоянии бага в LockService.
    // -----------------------------------------------------------------------
    @Test
    void whenLockAcquired_allUnprocessedReceiptsAreSentToTax() {
        LockService lockService = mock(LockService.class);
        when(lockService.lock(anyString())).thenReturn(false); // лок получен

        Receipt r1 = Receipt.builder().id(1L).sum("100").processed(false).build();
        Receipt r2 = Receipt.builder().id(2L).sum("200").processed(false).build();

        ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
        when(receiptRepository.findAllByProcessedFalse(20)).thenReturn(List.of(r1, r2));
        when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShedlockRepository shedlockRepository = mock(ShedlockRepository.class);
        when(shedlockRepository.findByName(anyString())).thenReturn(Optional.of(
                Shedlock.builder().status(ShedlockStatus.IN_PROCESS).build()
        ));

        TaxClient taxClient = mock(TaxClient.class);

        SendReceiptJob job = new SendReceiptJob(receiptRepository, shedlockRepository, taxClient, lockService);
        job.processReceipt();

        verify(taxClient).sendReceipt(1L, "100");
        verify(taxClient).sendReceipt(2L, "200");
        assertThat(r1.isProcessed()).isTrue();
        assertThat(r2.isProcessed()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Тест 3: Два потока вызывают processReceipt одновременно.
    // Ожидаемое поведение: taxClient.sendReceipt вызывается ровно для каждого
    // чека по 1 разу (второй поток заблокирован локом).
    //
    // ПАДАЕТ — пока не пофикшен race condition в LockService.lock():
    // оба потока проходят лок и каждый чек отправляется дважды.
    // -----------------------------------------------------------------------
    @Test
    void whenTwoThreadsCallProcessReceipt_onlyOneThreadSendsToTax() throws InterruptedException {
        // --- настраиваем LockService с реальной логикой, но мокнутым репозиторием ---
        CountDownLatch bothInsideFindByName = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);

        ShedlockRepository shedlockRepository = mock(ShedlockRepository.class);
        when(shedlockRepository.findByName(anyString())).thenAnswer(invocation -> {
            bothInsideFindByName.countDown();
            releaseAll.await(); // ждём второй поток — воспроизводим гонку
            return Optional.empty();
        });
        when(shedlockRepository.save(any(Shedlock.class))).thenAnswer(inv -> inv.getArgument(0));

        LockService lockService = new LockService(shedlockRepository);

        // --- чеки ---
        Receipt r1 = Receipt.builder().id(1L).sum("100").processed(false).build();
        Receipt r2 = Receipt.builder().id(2L).sum("200").processed(false).build();

        ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
        when(receiptRepository.findAllByProcessedFalse(20)).thenReturn(List.of(r1, r2));
        when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger taxCallCount = new AtomicInteger(0);
        TaxClient taxClient = mock(TaxClient.class);
        doAnswer(inv -> {
            taxCallCount.incrementAndGet();
            return null;
        })
                .when(taxClient).sendReceipt(any(), any());

        SendReceiptJob job = new SendReceiptJob(receiptRepository, shedlockRepository, taxClient, lockService);

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread t1 = new Thread(() -> {
            try {
                job.processReceipt();
            } catch (Exception e) {
                errors.add(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                job.processReceipt();
            } catch (Exception e) {
                errors.add(e);
            }
        });

        t1.start();
        t2.start();
        bothInsideFindByName.await();
        releaseAll.countDown();
        t1.join();
        t2.join();

        assertThat(errors).isEmpty();

        // Ожидаемое поведение: 2 чека × 1 поток = 2 вызова.
        // ПАДАЕТ при баге: 2 чека × 2 потока = 4 вызова.
        assertThat(taxCallCount.get())
                .as("taxClient.sendReceipt должен быть вызван ровно 2 раза (по одному на чек). " +
                        "Если 4 — оба потока прошли лок (race condition не пофикшен).")
                .isEqualTo(2);
    }
}
