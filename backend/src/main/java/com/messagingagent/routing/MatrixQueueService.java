package com.messagingagent.routing;

import com.google.common.util.concurrent.RateLimiter;
import com.messagingagent.device.DeviceWebSocketService;
import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.model.Device;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixQueueService {

    private final MatrixRouteService matrixRouteService;
    private final MessageLogRepository messageLogRepository;
    private final DeviceRepository deviceRepository;
    private final SimpMessagingTemplate messagingTemplate;
    
    // Lazy inject to avoid circular dependency since DeviceWebSocketService uses MatrixQueueService
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private DeviceWebSocketService deviceWebSocketService;

    // A memory queue holding up to 20,000 pending Matrix messages to prevent blocking the Device LB
    private final LinkedBlockingQueue<MatrixDispatchTask> dispatchQueue = new LinkedBlockingQueue<>(20000);
    
    // Strict requirement: Exactly 200 dispatches per second
    private final RateLimiter dispatchRateLimiter = RateLimiter.create(200.0);
    
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private Thread workerThread;

    public record MatrixDispatchTask(Device target, MessageLog queued) {}

    @PostConstruct
    public void startWorker() {
        workerThread = new Thread(() -> {
            while (isRunning.get() || !dispatchQueue.isEmpty()) {
                try {
                    MatrixDispatchTask task = dispatchQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        // Enforce precisely 200/s prior to executing external API request
                        dispatchRateLimiter.acquire();
                        processTask(task.target(), task.queued());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Fatal error inside Matrix internal worker loop", e);
                }
            }
        });
        workerThread.setName("Matrix-RateLimiter-Worker");
        workerThread.start();
    }

    @PreDestroy
    public void stopWorker() {
        isRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    /**
     * Enqueues a message for rate-limited Matrix dispatch.
     * Guaranteed non-blocking (returns immediately) allowing WebSocket drains of 10k/sec instantly.
     */
    public boolean enqueue(Device target, MessageLog queued) {
        boolean accepted = dispatchQueue.offer(new MatrixDispatchTask(target, queued));
        if (!accepted) {
            log.error("Matrix dispatch bounded queue is FULL (20,000)! Rejecting internal dispatch.");
            // Should theoretically never happen unless Synapse goes completely offline for > 100 seconds
        }
        return accepted;
    }

    /**
     * Handles the actual HTTP communication logic taken directly from original synchronized drain method.
     */
    private void processTask(Device target, MessageLog queued) {
        try {
            log.info("Dispatching async queued message through Matrix Bridge for {}", target.getName());
            String eventId = matrixRouteService.sendMessage(target, queued.getDestinationAddress(), queued.getMessageText());

            if (eventId == null) {
                int attempts = queued.getDispatchAttempts() != null ? queued.getDispatchAttempts() : 0;
                attempts++;
                queued.setDispatchAttempts(attempts);

                if (attempts >= 3) {
                    log.error("Matrix Gateway Error persisting for msg {}. Falling back to ERROR.", queued.getSmppMessageId());
                    messageLogRepository.save(queued);

                    SmsDeliveryResultEvent dlr = new SmsDeliveryResultEvent();
                    dlr.setCorrelationId(queued.getSmppMessageId());
                    dlr.setResult(SmsDeliveryResultEvent.Result.ERROR);
                    dlr.setErrorDetail("Matrix Gateway Error");
                    java.util.concurrent.CompletableFuture.runAsync(() -> deviceWebSocketService.handleDeliveryResult(dlr, target.getRegistrationToken()));
                } else {
                    log.warn("Matrix Gateway Error for msg {}, requeuing (Attempt {}/3)", queued.getSmppMessageId(), attempts);
                    queued.setStatus(MessageLog.Status.QUEUED);
                    queued.setDevice(null);
                    queued.setDispatchedAt(null);
                    messageLogRepository.save(queued);

                    // Unlock device natively because we are re-queuing it
                    target.setInFlightDispatches(Math.max(0, (target.getInFlightDispatches() != null ? target.getInFlightDispatches() : 0) - 1));
                    deviceRepository.decrementInFlight(target.getId());
                    messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", target.getId(), "inFlightDispatches", target.getInFlightDispatches()));
                    
                    // We don't trigger the direct interval fallback here since it runs asynchronously.
                }
            } else {
                // Save Matrix Event ID into supplierMessageId for the Sync Task
                queued.setSupplierMessageId(eventId);
                messageLogRepository.save(queued);
                
                // Emit TRACK_DLR_ONLY to the Android agent for FastTrack native polling
                if (queued.getMessageText() != null) {
                    try {
                        String b64Text = java.util.Base64.getEncoder().encodeToString(queued.getMessageText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        String cmd = "TRACK_DLR_ONLY=" + queued.getSmppMessageId() + "|" + queued.getDestinationAddress() + "|" + b64Text;
                        deviceRepository.findByGroup(target.getGroup()).forEach(dev -> {
                            if (dev.getStatus() == Device.Status.ONLINE || dev.getStatus() == Device.Status.BUSY) {
                                deviceWebSocketService.sendSysCommand(dev, cmd);
                            }
                        });
                        log.debug("Sent TRACK_DLR_ONLY to all devices in group {} for correlationId {}", target.getGroup().getId(), queued.getSmppMessageId());
                    } catch (Exception ex) {
                        log.warn("Failed to encode TRACK_DLR_ONLY for {}: {}", queued.getSmppMessageId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Matrix queue processor threw an exception processing {}: {}", queued.getSmppMessageId(), e.getMessage());
        }
    }
}
