package io.github.secondfry.discordrelay;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import computer.heather.advancedbackups.core.backups.BackupStatusInstance;
import computer.heather.advancedbackups.core.backups.BackupStatusInstance.State;
import computer.heather.advancedbackups.core.backups.ThreadedBackup;
import computer.heather.advancedbackups.interfaces.IClientContactor;

public class BackupNotificationsTest {

    @Test
    public void postsEachStateOnceAndAlwaysDelegates() {
        List<String> posts = new ArrayList<>();
        List<State> delegated = new ArrayList<>();
        BackupNotifications contactor = new BackupNotifications(new Recorder(delegated), posts::add);
        ThreadedBackup.lastStats = " Type: full";
        State[] states = { State.STARTING, State.STARTING, State.STARTED, State.STARTED, State.COMPLETE, State.COMPLETE,
            State.FAILED, State.CANCELLED };
        long[] ages = { 1, 1, 2, 3, 4, 4, 5, 6 };
        for (int i = 0; i < states.length; i++) contactor.handle(instance(states[i], ages[i]), true);
        assertEquals(Arrays.asList(states), delegated);
        assertEquals(
            Arrays.asList(
                "Server backup started.",
                "Server backup done. Type: full",
                "Server backup failed! Check the server log.",
                "Server backup cancelled."),
            posts);
    }

    @Test
    public void relayFailureKeepsDelegating() {
        List<State> delegated = new ArrayList<>();
        BackupNotifications contactor = new BackupNotifications(
            new Recorder(delegated),
            text -> { throw new IllegalStateException("boom"); });
        contactor.handle(instance(State.STARTING, 1), false);
        contactor.handle(instance(State.COMPLETE, 2), false);
        assertEquals(Arrays.asList(State.STARTING, State.COMPLETE), delegated);
    }

    private static BackupStatusInstance instance(State state, long age) {
        BackupStatusInstance instance = new BackupStatusInstance();
        instance.setState(state);
        instance.setAge(age);
        return instance;
    }

    @SuppressWarnings("deprecation")
    private static final class Recorder implements IClientContactor {

        private final List<State> delegated;

        Recorder(List<State> delegated) {
            this.delegated = delegated;
        }

        @Override
        public void handle(BackupStatusInstance instance, boolean all) {
            delegated.add(instance.getState());
        }

        @Override
        public void backupStarting(boolean all) {}

        @Override
        public void backupProgress(int current, int max, boolean all) {}

        @Override
        public void backupComplete(boolean all) {}

        @Override
        public void backupFailed(boolean all) {}

        @Override
        public void backupCancelled(boolean all) {}
    }
}
