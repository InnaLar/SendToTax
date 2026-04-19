package com.example.demo.services;

import com.example.demo.model.entity.Shedlock;
import com.example.demo.model.entity.ShedlockStatus;
import com.example.demo.repository.ShedlockRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@AllArgsConstructor
public class LockService {
    private final ShedlockRepository shedlockRepository;

    @Transactional
    public boolean lock(String name) {
        Optional<Shedlock> byName = shedlockRepository.findByName(name);
        if (byName.isEmpty()) {
            shedlockRepository.save(Shedlock.builder()
                    .name(name)
                    .status(ShedlockStatus.IN_PROCESS)
                    .build());
            return false;
        }

        Shedlock shedlock = byName.get();
        if (shedlock.getStatus().equals(ShedlockStatus.IN_PROCESS)) {
            return true;
        } else {
            shedlock.setStatus(ShedlockStatus.IN_PROCESS);
            return false;
        }

    }
}
