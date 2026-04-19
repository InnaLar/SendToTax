package com.example.demo.services;

import com.example.demo.model.entity.Shedlock;
import com.example.demo.repository.ShedlockRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Воспроизводит race condition в LockService.lock():
 * <p>
 * Оба потока вызывают findByName до того, как кто-либо сделал save.
 * Оба видят Optional.empty() → оба вставляют запись → оба возвращают false
 * (т.е. оба считают, что захватили лок и продолжают работу).
 * <p>
 * FOR UPDATE в findByName помогает только когда запись УЖЕ существует.
 * При первом старте, когда записи нет, блокировать нечего — отсюда и гонка.
 */
class LockServiceRaceConditionTest {

    @Test
    void twoThreadsBothAcquireLock_whenRowDoesNotExistYet() throws InterruptedException {
        // Latch: обе нити вошли в findByName — теперь отпускаем их одновременно
        CountDownLatch bothInsideFindByName = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);

        ShedlockRepository mockRepo = mock(ShedlockRepository.class);

        // Имитируем состояние гонки: оба потока видят пустую таблицу
        when(mockRepo.findByName(anyString())).thenAnswer(invocation -> {
            bothInsideFindByName.countDown();   // сообщаем: я внутри findByName
            releaseAll.await();                  // жду, пока второй поток тоже зайдёт
            return Optional.empty();             // оба видят — записи нет
        });
        when(mockRepo.save(any(Shedlock.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LockService lockService = new LockService(mockRepo);

        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        Thread t1 = new Thread(() -> results.add(lockService.lock("send-receipt-job")));
        Thread t2 = new Thread(() -> results.add(lockService.lock("send-receipt-job")));

        t1.start();
        t2.start();

        // Ждём, пока оба потока окажутся внутри findByName, затем отпускаем
        bothInsideFindByName.await();
        releaseAll.countDown();

        t1.join();
        t2.join();

        // Ожидаемое поведение: ровно один поток получил лок (false = "иди работай"),
        // второй увидел занятый лок (true = "заблокирован").
        // ПАДАЕТ — пока не пофикшен race condition:
        // оба потока видят Optional.empty() до первого save и оба возвращают false.
        assertThat(results)
                .as("Один поток должен быть заблокирован (true), второй — работать (false). " +
                    "Если оба false — race condition не пофикшен.")
                .containsExactlyInAnyOrder(false, true);
    }
}
