package io.github.secondfry.discordrelay;

import java.util.function.Consumer;

import computer.heather.advancedbackups.core.ABCore;
import computer.heather.advancedbackups.core.backups.BackupStatusInstance;
import computer.heather.advancedbackups.core.backups.ThreadedBackup;
import computer.heather.advancedbackups.interfaces.IClientContactor;

// Wraps AdvancedBackups' client contactor so its status updates also reach Discord; AB keeps its own behaviour.
@SuppressWarnings("deprecation")
final class BackupNotifications implements IClientContactor {

    private final IClientContactor delegate;
    private final Consumer<String> post;
    private long lastAge = -1;
    private BackupStatusInstance.State lastState;
    private boolean disabled;

    BackupNotifications(IClientContactor delegate, Consumer<String> post) {
        this.delegate = delegate;
        this.post = post;
    }

    static void install(DiscordBridge bridge) {
        try {
            IClientContactor original = ABCore.clientContactor;
            if (original == null || original instanceof BackupNotifications) return;
            ABCore.clientContactor = new BackupNotifications(original, bridge::server);
        } catch (LinkageError | RuntimeException failure) {
            DiscordRelay.LOG.warn("AdvancedBackups relay disabled after an integration error", failure);
        }
    }

    @Override
    public void handle(BackupStatusInstance instance, boolean all) {
        delegate.handle(instance, all);
        if (disabled) return;
        try {
            relay(instance);
        } catch (LinkageError | RuntimeException failure) {
            disabled = true;
            DiscordRelay.LOG.warn("Disabling AdvancedBackups relay after an integration error", failure);
        }
    }

    private void relay(BackupStatusInstance instance) {
        BackupStatusInstance.State state = instance.getState();
        // BackupTimer re-sends the current instance, so post once per published instance.
        if (instance.getAge() == lastAge && state == lastState) return;
        lastAge = instance.getAge();
        lastState = state;
        String text = message(state);
        if (text != null) post.accept(text);
    }

    private static String message(BackupStatusInstance.State state) {
        switch (state) {
            case STARTING:
                return "Server backup started.";
            case COMPLETE:
                return "Server backup done." + stats();
            case FAILED:
                return "Server backup failed! Check the server log.";
            case CANCELLED:
                return "Server backup cancelled.";
            default:
                return null;
        }
    }

    private static String stats() {
        try {
            String stats = ThreadedBackup.lastStats;
            return stats == null ? "" : stats;
        } catch (LinkageError upstream) {
            return ""; // lastStats exists only in the secondfry fork.
        }
    }

    @Override
    public void backupStarting(boolean all) {
        delegate.backupStarting(all);
    }

    @Override
    public void backupProgress(int current, int max, boolean all) {
        delegate.backupProgress(current, max, all);
    }

    @Override
    public void backupComplete(boolean all) {
        delegate.backupComplete(all);
    }

    @Override
    public void backupFailed(boolean all) {
        delegate.backupFailed(all);
    }

    @Override
    public void backupCancelled(boolean all) {
        delegate.backupCancelled(all);
    }
}
