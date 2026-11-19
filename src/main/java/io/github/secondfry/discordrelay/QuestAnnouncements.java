package io.github.secondfry.discordrelay;

import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.util.StatCollector;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.events.QuestEvent;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.utils.UuidConverter;
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
                String name = questName(id, quest);
                bridge.server(player + " completed quest [" + name + "].");
            }
        } catch (LinkageError | RuntimeException failure) {
            disabled = true;
            DiscordRelay.LOG.warn("Disabling BetterQuesting relay after an integration error", failure);
        }
    }

    private static String questName(UUID id, IQuest quest) {
        String key = "betterquesting.quest." + UuidConverter.encodeUuidStripPadding(id) + ".name";
        String name = quest.getProperty(NativeProps.NAME);
        if (StatCollector.canTranslate(key)) name = StatCollector.translateToLocal(key);
        else if (name != null && StatCollector.canTranslate(name)) name = StatCollector.translateToLocal(name);
        return name == null || name.isEmpty() ? id.toString() : name;
    }
}
