package com.messagingagent.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TopUpRequestDto {
    private BigDecimal amount;
    private String description;
}
