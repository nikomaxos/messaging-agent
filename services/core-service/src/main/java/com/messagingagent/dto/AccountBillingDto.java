package com.messagingagent.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AccountBillingDto {
    private Long accountId;
    private String billingType;
    private BigDecimal balance;
    private BigDecimal creditLimit;
    private Long tariffPlanId;
    private String tariffPlanName;
}
