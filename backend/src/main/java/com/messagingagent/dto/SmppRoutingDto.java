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
    
    private boolean isDefault;
    private boolean loadBalancerEnabled;
    private boolean resendEnabled;
    private Long fallbackSmscId;
    private String fallbackSmscName;
    private String resendTrigger;
    private Integer rcsExpirationSeconds;
    
    private List<DestinationDto> destinations;
    private Instant createdAt;
    private Instant updatedAt;

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

        public static DestinationDto fromEntity(SmppRoutingDestination dest) {
            return DestinationDto.builder()
                    .id(dest.getId())
                    .deviceGroupId(dest.getDeviceGroup().getId())
                    .deviceGroupName(dest.getDeviceGroup().getName())
                    .weightPercent(dest.getWeightPercent())
                    .fallbackSmscId(dest.getFallbackSmsc() != null ? dest.getFallbackSmsc().getId() : null)
                    .fallbackSmscName(dest.getFallbackSmsc() != null ? dest.getFallbackSmsc().getName() : null)
                    .build();
        }
    }

    public static SmppRoutingDto fromEntity(SmppRouting routing) {
        return SmppRoutingDto.builder()
                .id(routing.getId())
                .smppClientId(routing.getSmppClient().getId())
                .smppClientName(routing.getSmppClient().getName())
                .smppClientSystemId(routing.getSmppClient().getSystemId())
                .isDefault(routing.isDefault())
                .loadBalancerEnabled(routing.isLoadBalancerEnabled())
                .resendEnabled(routing.isResendEnabled())
                .fallbackSmscId(routing.getFallbackSmsc() != null ? routing.getFallbackSmsc().getId() : null)
                .fallbackSmscName(routing.getFallbackSmsc() != null ? routing.getFallbackSmsc().getName() : null)
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
