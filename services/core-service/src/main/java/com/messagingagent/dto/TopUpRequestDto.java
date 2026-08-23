package com.messagingagent.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TopUpRequestDto {
    private Long accountId;
    private BigDecimal amount;
    private String description;
}
