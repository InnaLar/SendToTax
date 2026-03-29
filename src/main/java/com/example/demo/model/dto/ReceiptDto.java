package com.example.demo.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReceiptDto {
    private Long id;
    private String sum;
}
