package com.messagingagent.dto;

import lombok.Data;

@Data
public class SmscSupplierRequestDto {
    private String name;
    private Long accountId;
    private String host;
    private int port;
    private String systemId;
    private String password;
    private String systemType;
    private String bindType = "TRANSCEIVER";
    private int maxBinds = 1;
    private String addressRange;
    private int sourceTon = 0;
    private int sourceNpi = 0;
    private int destTon = 0;
    private int destNpi = 0;
    private int throughput = 0;
    private int enquireLinkInterval = 30000;
    private boolean active = true;
    private boolean bypassDuplicateFilter = false;
    private String triggerResendErrorCodes;
}
