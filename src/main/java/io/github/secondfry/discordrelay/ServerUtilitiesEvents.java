package io.github.secondfry.discordrelay;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import serverutils.ServerUtilitiesConfig;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.ServerUtils;

final class ServerUtilitiesEvents {

    private final DiscordBridge bridge;
    private final Set<UUID> afk = new HashSet<>();
    private boolean afkFailed;
    private boolean visibilityFailed;

    ServerUtilitiesEvents(DiscordBridge bridge) {
        this.bridge = bridge;
    }

    boolean visible(EntityPlayer player, boolean chat) {
        if (visibilityFailed) return false;
        try {
            if (!ServerUtils.isVanished(player)) return true;
            if (!chat) return false;
            ServerUtilitiesPlayerData data = ServerUtilitiesPlayerData.getNullable(player);
            return data != null && data.getVanishData().chat;
        } catch (LinkageError | RuntimeException failure) {
            visibilityFailed = true;
            DiscordRelay.LOG.warn("Player relay disabled after a ServerUtilities visibility error", failure);
            return false;
        }
    }

    boolean visible(UUID id) {
        Universe universe = Universe.getNullable();
        if (universe == null) return false;
        ForgePlayer player = universe.getPlayer(id);
        if (player == null) return false;
        EntityPlayerMP online = player.getNullablePlayer();
        return online != null && visible(online, false);
    }

    void left(UUID id) {
        afk.remove(id);
    }

    void poll(MinecraftServer server) {
        if (afkFailed) return;
        try {
            pollAfk(server);
        } catch (LinkageError | RuntimeException failure) {
            afkFailed = true;
            DiscordRelay.LOG.warn("Disabling AFK relay after a ServerUtilities error", failure);
        }
    }

    private void pollAfk(MinecraftServer server) {
        if (!ServerUtilitiesConfig.afk.isEnabled(server)) {
            afk.clear();
            return;
        }
        Universe universe = Universe.getNullable();
        if (universe == null) return;
        long threshold = ServerUtilitiesConfig.afk.getNotificationTimer();
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            UUID id = player.getUniqueID();
            ForgePlayer forgePlayer = universe.getPlayer(player.getGameProfile());
            if (forgePlayer == null || ServerUtils.isFake(player) || !visible(player, false)) {
                afk.remove(id);
                continue;
            }
            boolean nowAfk = ServerUtilitiesPlayerData.get(forgePlayer).afkTime >= threshold;
            boolean changed = nowAfk ? afk.add(id) : afk.remove(id);
            if (!changed) continue;
            bridge.server(player.getCommandSenderName() + (nowAfk ? " is now AFK." : " is back."));
        }
    }
}
