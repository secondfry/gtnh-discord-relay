package io.github.secondfry.discordrelay;

import java.util.UUID;
import java.util.function.Predicate;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.events.QuestEvent;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api2.utils.QuestTranslation;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public final class QuestAnnouncements {

    private final DiscordBridge bridge;
    private final Predicate<UUID> visible;
    private boolean disabled;

    QuestAnnouncements(DiscordBridge bridge, Predicate<UUID> visible) {
        this.bridge = bridge;
        this.visible = visible;
    }

    @SubscribeEvent
    public void completed(QuestEvent event) {
        if (disabled) return;
        try {
            if (event.getType() != QuestEvent.Type.COMPLETED || event.getQuestIDs()
                .isEmpty()) return;
            if (!visible.test(event.getPlayerID())) return;
            String player = QuestingAPI.getAPI(ApiReference.NAME_CACHE)
                .getName(event.getPlayerID());
            if (player == null || player.isEmpty()) return;
            for (UUID id : event.getQuestIDs()) {
                IQuest quest = QuestingAPI.getAPI(ApiReference.QUEST_DB)
                    .get(id);
                if (quest == null || Boolean.TRUE.equals(quest.getProperty(NativeProps.SILENT))) continue;
                String name = QuestTranslation.translateQuestName(id, quest);
                if (name == null || name.isEmpty()) name = id.toString();
                bridge.server(player + " completed quest [" + name + "].");
            }
        } catch (LinkageError | RuntimeException failure) {
            disabled = true;
            DiscordRelay.LOG.warn("Disabling BetterQuesting relay after an integration error", failure);
        }
    }

}
