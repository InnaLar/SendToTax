package com.example.demo.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "shedlock")
@Getter
@Setter
public class Shedlock {
    @Id
    @Column
    private Long id;
    @Column
    private String name;
    @Column(name = "start_time")
    private Instant startTime;
    @Column
    @Enumerated(EnumType.STRING)
    private ShedlockStatus status;
}
