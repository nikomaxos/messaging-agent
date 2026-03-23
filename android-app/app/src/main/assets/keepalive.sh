#!/system/bin/sh
# MessagingAgent Keepalive Watchdog
# Runs as a detached root process, survives app force-stop.
# Checks every 30s if the app process is alive; restarts if dead.

PACKAGE="com.messagingagent.android"
SERVICE="$PACKAGE/.service.MessagingAgentService"
LOCKFILE="/data/local/tmp/ma_keepalive.lock"
LOGFILE="/data/local/tmp/ma_keepalive.log"

# Prevent duplicate instances
if [ -f "$LOCKFILE" ]; then
    OLD_PID=$(cat "$LOCKFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCKFILE"

log() {
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$TIMESTAMP] $1" >> "$LOGFILE"
    # Keep log file under 50KB
    if [ $(wc -c < "$LOGFILE" 2>/dev/null || echo 0) -gt 51200 ]; then
        tail -100 "$LOGFILE" > "${LOGFILE}.tmp"
        mv "${LOGFILE}.tmp" "$LOGFILE"
    fi
}

log "Keepalive watchdog started (PID=$$)"

while true; do
    sleep 30

    # Check if app process is running
    if ! pidof "$PACKAGE" > /dev/null 2>&1; then
        log "App not running — restarting service"

        # Clear the force-stopped flag so receivers work again
        am set-stopped-state "$PACKAGE" false 2>/dev/null

        # Start the foreground service
        am startservice -n "$SERVICE" 2>/dev/null || \
        am start-foreground-service -n "$SERVICE" 2>/dev/null

        # Also launch the activity as a fallback (some ROMs need this)
        am start -n "$PACKAGE/.ui.SetupActivity" \
            --ez autostart true \
            -f 0x10000000 2>/dev/null

        log "Restart commands sent"
        sleep 10  # Give it time to start before next check
    fi
done
