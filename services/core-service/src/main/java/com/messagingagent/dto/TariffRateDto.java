package com.messagingagent.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TariffRateDto {
    private Long id;
    private Long planId;
    private String prefix;
    private BigDecimal rate;
}
