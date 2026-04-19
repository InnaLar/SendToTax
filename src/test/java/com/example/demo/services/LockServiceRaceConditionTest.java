package com.example.demo.services;

import com.example.demo.clients.TaxClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Тестирует race condition в LockService.lock() с реальной транзакцией PostgreSQL.
 * <p>
 * Проблема: SELECT ... FOR UPDATE блокирует только существующие строки.
 * Когда строки нет, два потока одновременно видят пустой результат,
 * оба вставляют новую запись и оба считают, что получили лок.
 * <p>
 * Тест ПАДАЕТ — пока баг не пофикшен.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LockServiceRaceConditionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // Отключаем HTTP-клиент и генератор чеков — не нужны для этих тестов
    @MockitoBean
    TaxClient taxClient;
    @MockitoBean
    ReceiptPopulationJob receiptPopulationJob;

    // Реальный Spring-бин через @Transactional-прокси
    @Autowired
    LockService lockService;

    // Spy-обёртка вокруг реального репозитория — нужна для вставки латча
    @MockitoSpyBean
    ShedlockRepository shedlockRepository;

    @BeforeEach
    void cleanup() {
        shedlockRepository.deleteAll();
    }

    /**
     * Два потока вызывают lock() одновременно, пока в таблице нет ни одной строки.
     * <p>
     * Ожидаемое поведение: один поток получает лок (false = "иди работай"),
     * второй видит занятый лок (true = "заблокирован").
     * <p>
     * Реальное поведение (баг): оба возвращают false — оба начинают работу.
     * <p>
     * ТЕСТ ПАДАЕТ.
     */
    @Test
    void twoThreadsBothAcquireLock_whenRowDoesNotExistYet() throws InterruptedException {
        CountDownLatch bothInsideFindByName = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);

        // Перехватываем findByName: ждём, пока оба потока окажутся внутри SELECT,
        // только потом пускаем их дальше. Это гарантирует, что оба читают
        // пустую таблицу до того, как кто-либо сделал INSERT.
        doAnswer(inv -> {
            bothInsideFindByName.countDown(); // "я внутри SELECT"
            releaseAll.await();               // жду второй поток
            return inv.callRealMethod();      // реальный SELECT ... FOR UPDATE в транзакции
        }).when(shedlockRepository).findByName(anyString());

        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        Thread t1 = new Thread(() -> results.add(lockService.lock("send-receipt-job")));
        Thread t2 = new Thread(() -> results.add(lockService.lock("send-receipt-job")));

        t1.start();
        t2.start();

        bothInsideFindByName.await(); // оба внутри SELECT
        releaseAll.countDown();       // отпускаем одновременно

        t1.join();
        t2.join();

        // Корректное поведение: ровно один false (лок захвачен) и один true (заблокирован)
        // ПАДАЕТ: оба вернули false — race condition не пофикшен
        assertThat(results)
                .as("Один поток должен быть заблокирован (true), второй — работать (false). " +
                        "Если оба false — SELECT FOR UPDATE не защищает от вставки двух строк.")
                .containsExactlyInAnyOrder(false, true);
    }
}
