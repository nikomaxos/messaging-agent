package com.messagingagent.adb;

import com.messagingagent.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service that wraps ADB command execution for the Remote Desktop feature.
 * Uses ProcessBuilder to run adb commands against devices via their WiFi ADB addresses.
 */
@Service
public class AdbService {

    private static final Logger log = LoggerFactory.getLogger(AdbService.class);
    private static final int COMMAND_TIMEOUT_SECONDS = 10;
    private static final int SCREENSHOT_TIMEOUT_SECONDS = 4;

    /**
     * Daemon scheduler used to kill hanging screencap processes after timeout.
     */
    private final ScheduledExecutorService screenshotKiller =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "screencap-killer");
                t.setDaemon(true);
                return t;
            });

    /**
     * Track which device addresses have been connected to avoid reconnecting every request.
     */
    private final Set<String> connectedDevices = ConcurrentHashMap.newKeySet();

    /**
     * Cache device screen resolutions to avoid querying repeatedly.
     * Key: deviceId, Value: [width, height]
     */
    private final Map<Long, int[]> screenResolutions = new ConcurrentHashMap<>();

    /**
     * Ensure ADB is connected to the device's WiFi address.
     */
    public boolean ensureConnected(Device device) {
        String addr = device.getAdbWifiAddress();
        if (addr == null || addr.isBlank()) {
            log.warn("Device {} has no ADB WiFi address", device.getId());
            return false;
        }
        // Normalize: ensure port is specified
        if (!addr.contains(":")) {
            addr = addr + ":5555";
        }
        if (connectedDevices.contains(addr)) {
            return true;
        }
        try {
            String result = executeCommand(COMMAND_TIMEOUT_SECONDS, "adb", "connect", addr);
            if (result != null && (result.contains("connected") || result.contains("already"))) {
                connectedDevices.add(addr);
                log.info("ADB connected to {} (device {})", addr, device.getId());
                return true;
            }
            log.warn("ADB connect to {} failed: {}", addr, result);
            return false;
        } catch (Exception e) {
            log.error("ADB connect error for {}: {}", addr, e.getMessage());
            return false;
        }
    }

    /**
     * Capture a PNG screenshot from the device.
     * Uses 'adb exec-out screencap -p' which outputs raw PNG to stdout.
     * Has a hard timeout that kills the adb process if screencap hangs (e.g. sleeping device).
     */
    public byte[] captureScreenshot(Device device) throws IOException {
        if (!ensureConnected(device)) {
            throw new IOException("Cannot connect to device " + device.getId());
        }
        String addr = normalizeAddress(device.getAdbWifiAddress());

        ProcessBuilder pb = new ProcessBuilder("adb", "-s", addr, "exec-out", "screencap", "-p");
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        // Schedule a hard kill if the process doesn't finish in time.
        // This prevents is.read() from blocking the HTTP thread forever.
        java.util.concurrent.ScheduledFuture<?> killer = screenshotKiller.schedule(() -> {
            if (proc.isAlive()) {
                log.warn("Screenshot process for device {} timed out — killing", device.getId());
                proc.destroyForcibly();
            }
        }, SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        byte[] data;
        try (InputStream is = proc.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 512)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            data = baos.toByteArray();
        } finally {
            killer.cancel(false);
            if (proc.isAlive()) proc.destroyForcibly();
        }

        if (data.length < 100) {
            connectedDevices.remove(addr);
            throw new IOException("Screenshot too small (" + data.length + " bytes) — device may be disconnected or screen off");
        }

        return data;
    }

    /**
     * Send a tap event at the given coordinates.
     */
    public void sendTap(Device device, int x, int y) throws IOException {
        executeDeviceShell(device, "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    /**
     * Send a swipe gesture.
     */
    public void sendSwipe(Device device, int x1, int y1, int x2, int y2, int durationMs) throws IOException {
        executeDeviceShell(device, "input", "swipe",
                String.valueOf(x1), String.valueOf(y1),
                String.valueOf(x2), String.valueOf(y2),
                String.valueOf(durationMs));
    }

    /**
     * Send a key event (e.g. KEYCODE_HOME=3, KEYCODE_BACK=4, KEYCODE_APP_SWITCH=187).
     */
    public void sendKeyEvent(Device device, int keycode) throws IOException {
        executeDeviceShell(device, "input", "keyevent", String.valueOf(keycode));
    }

    /**
     * Wake the screen and dismiss the lock screen (swipe up).
     * Sends KEYCODE_WAKEUP (224), waits for screen to turn on,
     * then swipes up from bottom center to dismiss status/lock screen.
     */
    public void wakeAndUnlock(Device device) throws IOException {
        // 1. Prevent the screen from immediately dozing back off
        executeDeviceShell(device, "svc", "power", "stayon", "true");
        log.info("Device {} — svc power stayon true", device.getId());

        // 2. POWER key (26) reliably toggles screen on MIUI (224/WAKEUP is blocked in Doze)
        executeDeviceShell(device, "input", "keyevent", "26");
        log.info("Device {} — POWER keyevent sent", device.getId());
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 3. Check — if the screen turned off (was already awake), press again
        String power = executeDeviceShellRaw(device, "dumpsys", "power");
        boolean isAwake = power != null && power.contains("mWakefulness=Awake");
        if (!isAwake) {
            executeDeviceShell(device, "input", "keyevent", "26");
            log.info("Device {} — second POWER press (screen was on, toggled off)", device.getId());
            try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 4. Swipe up from bottom of screen to dismiss lock screen
        int[] res = getScreenResolution(device);
        int centerX = res[0] / 2;
        int bottomY = (int) (res[1] * 0.85);
        int topY = (int) (res[1] * 0.25);
        executeDeviceShell(device, "input", "swipe",
                String.valueOf(centerX), String.valueOf(bottomY),
                String.valueOf(centerX), String.valueOf(topY), "300");
        log.info("Device {} — Swipe unlock sent ({},{}) → ({},{})", device.getId(), centerX, bottomY, centerX, topY);
    }

    /**
     * Get device screen resolution. Cached after first query.
     */
    public int[] getScreenResolution(Device device) {
        return screenResolutions.computeIfAbsent(device.getId(), id -> {
            try {
                String result = executeDeviceShellRaw(device, "wm", "size");
                if (result != null) {
                    // Output: "Physical size: 1080x2400"
                    String[] parts = result.trim().split("\\s+");
                    String sizeStr = parts[parts.length - 1]; // "1080x2400"
                    String[] dims = sizeStr.split("x");
                    return new int[]{Integer.parseInt(dims[0]), Integer.parseInt(dims[1])};
                }
            } catch (Exception e) {
                log.warn("Could not get screen resolution for device {}: {}", id, e.getMessage());
            }
            return new int[]{1080, 2400}; // sensible default
        });
    }

    /**
     * Invalidate connection state (call when device disconnects).
     */
    public void invalidateConnection(String address) {
        connectedDevices.remove(address);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void executeDeviceShell(Device device, String... shellArgs) throws IOException {
        if (!ensureConnected(device)) {
            throw new IOException("Cannot connect to device " + device.getId());
        }
        String addr = normalizeAddress(device.getAdbWifiAddress());
        String[] cmd = new String[4 + shellArgs.length];
        cmd[0] = "adb";
        cmd[1] = "-s";
        cmd[2] = addr;
        cmd[3] = "shell";
        System.arraycopy(shellArgs, 0, cmd, 4, shellArgs.length);
        executeCommand(COMMAND_TIMEOUT_SECONDS, cmd);
    }

    private String executeDeviceShellRaw(Device device, String... shellArgs) throws IOException {
        if (!ensureConnected(device)) {
            throw new IOException("Cannot connect to device " + device.getId());
        }
        String addr = normalizeAddress(device.getAdbWifiAddress());
        String[] cmd = new String[4 + shellArgs.length];
        cmd[0] = "adb";
        cmd[1] = "-s";
        cmd[2] = addr;
        cmd[3] = "shell";
        System.arraycopy(shellArgs, 0, cmd, 4, shellArgs.length);
        return executeCommand(COMMAND_TIMEOUT_SECONDS, cmd);
    }

    private String normalizeAddress(String addr) {
        if (addr != null && !addr.contains(":")) {
            return addr + ":5555";
        }
        return addr;
    }

    private String executeCommand(int timeoutSeconds, String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String output;
        try (InputStream is = proc.getInputStream()) {
            output = new String(is.readAllBytes()).trim();
        }

        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        return output;
    }
}
