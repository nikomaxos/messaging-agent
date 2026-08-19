package com.messagingagent.smpp.model;

import lombok.Data;

@Data
public class SmscSupplier {
    private Long id;
    private String name;
    private String host;
    private int port;
    private String systemId;
    private String password;
    private String systemType;
    private String bindType;
    private int maxBinds = 1;
    private int sourceTon;
    private int sourceNpi;
    private int destTon;
    private int destNpi;
    private int enquireLinkInterval;
    private Integer maxSessionLifetime;
    private boolean bypassDuplicateFilter;
    private boolean active;
    private long sentCount;
    private String triggerResendErrorCodes;

}
