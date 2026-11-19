package io.github.secondfry.discordrelay;

import java.util.concurrent.TimeUnit;

final class ServerHeartbeat {

    enum Phase {

        STARTING(120, "Minecraft server startup has stalled for 120 seconds."),
        RUNNING(30, "Minecraft server loop has stalled for 30 seconds."),
        STOPPING(30, "Minecraft server shutdown has stalled for 30 seconds.");

        final long timeout;
        final String alert;

        Phase(long seconds, String alert) {
            this.timeout = TimeUnit.SECONDS.toNanos(seconds);
            this.alert = alert;
        }
    }

    private volatile long lastProgress;
    private volatile Phase phase;

    ServerHeartbeat(long now) {
        enter(Phase.STARTING, now);
    }

    void enter(Phase next, long now) {
        progress(now);
        phase = next;
    }

    void progress(long now) {
        lastProgress = now;
    }

    Phase stalled(long now) {
        Phase current = phase;
        return now - lastProgress >= current.timeout ? current : null;
    }
}
