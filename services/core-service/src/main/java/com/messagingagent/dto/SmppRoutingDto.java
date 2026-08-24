package com.messagingagent.dto;

import com.messagingagent.model.SmppRouting;
import com.messagingagent.model.SmppRoutingDestination;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmppRoutingDto {
    private Long id;
    private Long smppClientId;
    private String smppClientName;
    private String smppClientSystemId;
    
    private NetworkDto network;
    
    private boolean isDefault;
    private String routingMode;
    private boolean autoFailEnabled;
    private int autoFailTimeoutMinutes;

    private boolean emulateDelivery;
    private String emulatedErrorCode;

    private boolean loadBalancerEnabled;
    private boolean resendEnabled;
    private Long fallbackSmscId;
    private String fallbackSmscName;
    private String fallbackRoutingMode;
    private Long fallbackDeviceGroupId;
    private String fallbackDeviceGroupName;
    private String fallbackErrorCodes;
    private String resendTrigger;
    private Integer rcsExpirationSeconds;
    
    private List<DestinationDto> destinations;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkDto {
        private Long id;
        private String name;
        private String countryName;
        private String isoCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DestinationDto {
        private Long id;
        private Long deviceGroupId;
        private String deviceGroupName;
        private int weightPercent;
        private Long fallbackSmscId;
        private String fallbackSmscName;
        private String fallbackRoutingMode;
        private Long fallbackDeviceGroupId;
        private String fallbackDeviceGroupName;
        private String fallbackErrorCodes;

        public static DestinationDto fromEntity(SmppRoutingDestination dest) {
            return DestinationDto.builder()
                    .id(dest.getId())
                    .deviceGroupId(dest.getDeviceGroup() != null ? dest.getDeviceGroup().getId() : null)
                    .deviceGroupName(dest.getDeviceGroup() != null ? dest.getDeviceGroup().getName() : null)
                    .weightPercent(dest.getWeightPercent())
                    .fallbackSmscId(dest.getFallbackSmsc() != null ? dest.getFallbackSmsc().getId() : null)
                    .fallbackSmscName(dest.getFallbackSmsc() != null ? dest.getFallbackSmsc().getName() : null)
                    .fallbackRoutingMode(dest.getFallbackRoutingMode() != null ? dest.getFallbackRoutingMode().name() : null)
                    .fallbackDeviceGroupId(dest.getFallbackDeviceGroup() != null ? dest.getFallbackDeviceGroup().getId() : null)
                    .fallbackDeviceGroupName(dest.getFallbackDeviceGroup() != null ? dest.getFallbackDeviceGroup().getName() : null)
                    .fallbackErrorCodes(dest.getFallbackErrorCodes())
                    .build();
        }
    }

    public static SmppRoutingDto fromEntity(SmppRouting routing) {
        return SmppRoutingDto.builder()
                .id(routing.getId())
                .smppClientId(routing.getSmppClient().getId())
                .smppClientName(routing.getSmppClient().getName())
                .smppClientSystemId(routing.getSmppClient().getSystemId())
                .network(routing.getNetwork() != null ? 
                    NetworkDto.builder()
                        .id(routing.getNetwork().getId())
                        .name(routing.getNetwork().getName())
                        .countryName(routing.getNetwork().getCountry().getName())
                        .isoCode(routing.getNetwork().getCountry().getIsoCode())
                        .build() : null)
                .isDefault(routing.isDefault())
                .routingMode(routing.getRoutingMode() != null ? routing.getRoutingMode().name() : "WEBSOCKET")
                .autoFailEnabled(routing.isAutoFailEnabled())
                .autoFailTimeoutMinutes(routing.getAutoFailTimeoutMinutes() != null && routing.getAutoFailTimeoutMinutes() != 0 ? routing.getAutoFailTimeoutMinutes() : 15)
                .emulateDelivery(routing.isEmulateDelivery())
                .emulatedErrorCode(routing.getEmulatedErrorCode())
                .loadBalancerEnabled(routing.isLoadBalancerEnabled())
                .resendEnabled(routing.isResendEnabled())
                .fallbackSmscId(routing.getFallbackSmsc() != null ? routing.getFallbackSmsc().getId() : null)
                .fallbackSmscName(routing.getFallbackSmsc() != null ? routing.getFallbackSmsc().getName() : null)
                .fallbackRoutingMode(routing.getFallbackRoutingMode() != null ? routing.getFallbackRoutingMode().name() : null)
                .fallbackDeviceGroupId(routing.getFallbackDeviceGroup() != null ? routing.getFallbackDeviceGroup().getId() : null)
                .fallbackDeviceGroupName(routing.getFallbackDeviceGroup() != null ? routing.getFallbackDeviceGroup().getName() : null)
                .fallbackErrorCodes(routing.getFallbackErrorCodes())
                .resendTrigger(routing.getResendTrigger())
                .rcsExpirationSeconds(routing.getRcsExpirationSeconds())
                .destinations(routing.getDestinations().stream()
                        .map(DestinationDto::fromEntity)
                        .collect(Collectors.toList()))
                .createdAt(routing.getCreatedAt())
                .updatedAt(routing.getUpdatedAt())
                .build();
    }
}
