package com.example.demo.services;

import com.example.demo.clients.TaxClient;
import com.example.demo.model.entity.Receipt;
import com.example.demo.model.entity.ReceiptSource;
import com.example.demo.model.entity.Shedlock;
import com.example.demo.model.entity.ShedlockStatus;
import com.example.demo.repository.ReceiptRepository;
import com.example.demo.repository.ShedlockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Тесты для SendReceiptJob.processReceipt() с реальным Spring-контекстом и PostgreSQL.
 *
 * Тесты 1 и 2 — документируют ожидаемое поведение системы (ПРОХОДЯТ).
 * Тест 3 — воспроизводит race condition end-to-end (ПАДАЕТ).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SendReceiptJobTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    TaxClient taxClient;
    @MockitoBean
    ReceiptPopulationJob receiptPopulationJob;

    // Spy на LockService — нужен для синхронизации потоков в тесте 3
    @MockitoSpyBean
    LockService lockService;

    @Autowired
    SendReceiptJob job;

    @Autowired
    ReceiptRepository receiptRepository;

    @Autowired
    ShedlockRepository shedlockRepository;

    @BeforeEach
    void cleanup() {
        receiptRepository.deleteAll();
        shedlockRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Тест 1: Лок занят (IN_PROCESS) — processReceipt не должен отправлять чеки.
    // Проверяет: lock() видит IN_PROCESS в БД → возвращает true → работа пропускается.
    // ПРОХОДИТ.
    // -----------------------------------------------------------------------
    @Test
    void whenLockIsInProcess_noReceiptsSentToTax() {
        shedlockRepository.save(Shedlock.builder()
                .name("processReceipt")
                .status(ShedlockStatus.IN_PROCESS)
                .build());

        receiptRepository.save(Receipt.builder()
                .sum("500")
                .source(ReceiptSource.SELL)
                .processed(false)
                .build());

        job.processReceipt();

        verify(taxClient, never()).sendReceipt(any(), any());
    }

    // -----------------------------------------------------------------------
    // Тест 2: Лок свободен (READY_TO_WORK) — все непроверенные чеки уходят в налоговую.
    // Проверяет: lock() видит READY_TO_WORK → возвращает false → чеки обрабатываются.
    // ПРОХОДИТ.
    // -----------------------------------------------------------------------
    @Test
    void whenLockIsReadyToWork_allUnprocessedReceiptsSentToTax() {
        shedlockRepository.save(Shedlock.builder()
                .name("processReceipt")
                .status(ShedlockStatus.READY_TO_WORK)
                .build());

        Receipt r1 = receiptRepository.save(Receipt.builder()
                .sum("100").source(ReceiptSource.SELL).processed(false).build());
        Receipt r2 = receiptRepository.save(Receipt.builder()
                .sum("200").source(ReceiptSource.REFUND).processed(false).build());

        job.processReceipt();

        verify(taxClient).sendReceipt(r1.getId(), "100");
        verify(taxClient).sendReceipt(r2.getId(), "200");
    }

    // -----------------------------------------------------------------------
    // Тест 3: Два потока вызывают processReceipt() одновременно.
    // Ожидаемое поведение: только один поток проходит лок, taxClient вызывается
    //                       ровно по одному разу на чек.
    //
    // ПАДАЕТ — пока не пофикшен race condition в LockService:
    // оба потока получают лок, каждый чек отправляется дважды.
    //
    // Примечание: unlock() в finally-блоке может выбросить
    // IncorrectResultSizeDataAccessException (два INSERT из-за гонки) —
    // это сопутствующий симптом того же бага.
    // -----------------------------------------------------------------------
    @Test
    void whenTwoThreadsCallProcessReceipt_onlyOneThreadSendsToTax() throws InterruptedException {
        receiptRepository.save(Receipt.builder()
                .sum("100").source(ReceiptSource.SELL).processed(false).build());
        receiptRepository.save(Receipt.builder()
                .sum("200").source(ReceiptSource.REFUND).processed(false).build());

        // Синхронизируем вход в lock(): оба потока стартуют одновременно,
        // до того как первый из них выполнит SELECT в БД.
        CountDownLatch bothInsideLock = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);

        doAnswer(inv -> {
            bothInsideLock.countDown();
            releaseAll.await();
            return inv.callRealMethod(); // реальный lock() с @Transactional
        }).when(lockService).lock(anyString());

        AtomicInteger taxCallCount = new AtomicInteger(0);
        doAnswer(inv -> { taxCallCount.incrementAndGet(); return null; })
                .when(taxClient).sendReceipt(any(), any());

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread t1 = new Thread(() -> { try { job.processReceipt(); } catch (Exception e) { errors.add(e); } });
        Thread t2 = new Thread(() -> { try { job.processReceipt(); } catch (Exception e) { errors.add(e); } });

        t1.start();
        t2.start();
        bothInsideLock.await();
        releaseAll.countDown();
        t1.join();
        t2.join();

        // Ожидаемое поведение: 2 чека × 1 поток = 2 вызова taxClient.
        // ПАДАЕТ при баге: 2 чека × 2 потока = 4 вызова.
        assertThat(taxCallCount.get())
                .as("taxClient.sendReceipt должен быть вызван ровно 2 раза (один поток). " +
                    "Если 4 — оба потока прошли лок (race condition не пофикшен).")
                .isEqualTo(2);
    }
}
