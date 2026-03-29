package com.example.demo.clients;

import com.example.demo.model.dto.ReceiptDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;

import static org.springframework.http.MediaType.APPLICATION_JSON;

@RequiredArgsConstructor
public class TaxClient {
    private final RestClient restClient;

    public void sendReceipt(Long id, String sum) {
        ReceiptDto receiptDto = ReceiptDto.builder()
                .id(id)
                .sum(sum)
                .build();
        restClient.post()
                .uri("/api/v1/tax")
                .contentType(APPLICATION_JSON)
                .body(receiptDto)
                .retrieve()
                .toBodilessEntity();
    }
}
