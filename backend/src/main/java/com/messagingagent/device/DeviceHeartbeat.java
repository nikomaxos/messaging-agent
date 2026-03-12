package com.messagingagent.device;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHeartbeat {
    private Integer batteryPercent;
    private Integer wifiSignalDbm;
    private Integer gsmSignalDbm;
    private Integer gsmSignalAsu;
    private String networkOperator;
    private Boolean rcsCapable;
}
