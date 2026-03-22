package com.example.demo.model.dto;

import com.example.demo.model.entity.ReceiptSource;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ReceiptDto {
    private Long id;
    private String sum;
    private ReceiptSource source;
}
