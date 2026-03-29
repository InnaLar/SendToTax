package com.example.demo.model.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
public class ReceiptDto {
    private Long id;
    private String sum;
}
