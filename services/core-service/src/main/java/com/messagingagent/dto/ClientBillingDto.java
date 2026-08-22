package com.messagingagent.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ClientBillingDto {
    private Long clientId;
    private String clientName;
    private String billingType;
    private BigDecimal balance;
    private BigDecimal creditLimit;
    private Long tariffPlanId;
    private String tariffPlanName;
}
