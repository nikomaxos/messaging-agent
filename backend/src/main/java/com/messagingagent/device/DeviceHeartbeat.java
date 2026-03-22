package com.messagingagent.device;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHeartbeat {
    private Integer batteryPercent;
    private Boolean isCharging;
    private Integer wifiSignalDbm;
    private Integer gsmSignalDbm;
    private Integer gsmSignalAsu;
    private String networkOperator;
    private Boolean rcsCapable;
    private String activeNetworkType;
    private String apkVersion;
    private String phoneNumber;
    private String adbWifiAddress;
}
