package com.example.demo.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "shedlock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shedlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
