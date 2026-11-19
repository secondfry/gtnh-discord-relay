package io.github.secondfry.discordrelay;

import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.StatisticsFile;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.GameRules;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AchievementEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@Mod(
    modid = DiscordRelay.MOD_ID,
    name = "Discord Relay",
    version = Tags.VERSION,
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:betterquesting;after:serverutilities")
public final class DiscordRelay {

    static final String MOD_ID = "discordrelay";
    static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.Instance(MOD_ID)
    public static DiscordRelay instance;

    private RelayConfig config;
    private MinecraftServer server;
    private ServerHeartbeat heartbeat; // Only the server thread owns the active reference.
    private DiscordBridge bridge;
    private boolean stopping;

    private QuestAnnouncements betterQuestingAnnouncements;
    private ServerUtilitiesEvents serverUtilitiesEvents;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (!event.getSide()
            .isServer()) return;
        Path directory = event.getModConfigurationDirectory()
            .toPath()
            .resolve(MOD_ID);
        config = new RelayConfig(directory);
    }

    @Mod.EventHandler
    public void aboutToStart(FMLServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer() || config == null) return;

        ServerHeartbeat heartbeat = new ServerHeartbeat(System.nanoTime());
        DiscordBridge bridge = new DiscordBridge(config, heartbeat);
        if (!bridge.isConfigured()) return;

        this.bridge = bridge;
        this.heartbeat = heartbeat;
        this.server = server;

        stopping = false;

        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);

        if (Loader.isModLoaded("serverutilities")) serverUtilitiesEvents = new ServerUtilitiesEvents(bridge);
        if (Loader.isModLoaded("betterquesting")) {
            betterQuestingAnnouncements = new QuestAnnouncements(
                bridge,
                id -> serverUtilitiesEvents == null || serverUtilitiesEvents.visible(id));
            MinecraftForge.EVENT_BUS.register(betterQuestingAnnouncements);
        }
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        if (bridge == null) return;
        event.registerServerCommand(new LinkCommand(true));
        event.registerServerCommand(new LinkCommand(false));
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        if (bridge == null) return;
        heartbeat.enter(ServerHeartbeat.Phase.RUNNING, System.nanoTime());
        bridge.server("Minecraft server started.");
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        stopping = true;
        if (bridge == null) return;
        heartbeat.enter(ServerHeartbeat.Phase.STOPPING, System.nanoTime());
    }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) {
        if (bridge == null) return;
        bridge.close(shutdownMessage());

        MinecraftForge.EVENT_BUS.unregister(this);
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);

        if (betterQuestingAnnouncements != null) MinecraftForge.EVENT_BUS.unregister(betterQuestingAnnouncements);

        betterQuestingAnnouncements = null;
        serverUtilitiesEvents = null;
        heartbeat = null;
        bridge = null;
        server = null;
    }

    private String shutdownMessage() {
        if (!stopping) return "Minecraft server crashed or failed to start.";
        if (server.isServerStopped()) return "Minecraft server stopped.";
        return "Minecraft server shutdown failed. Check the Minecraft server log.";
    }

    public void serverLoopHeartbeat() {
        if (heartbeat == null) return;
        heartbeat.progress(System.nanoTime());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (serverUtilitiesEvents != null) serverUtilitiesEvents.poll(server);
        bridge.tick(server);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void chat(ServerChatEvent event) {
        if (!isPublic(event.player, true)) return;
        bridge.player(event.player.getCommandSenderName(), event.message);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void achievement(AchievementEvent event) {
        // func_147136_ar reads announce-player-achievements.
        if (!server.func_147136_ar() || !isPublic(event.entityPlayer, false)) return;
        StatisticsFile stats = ((EntityPlayerMP) event.entityPlayer).func_147099_x();
        if (stats.hasAchievementUnlocked(event.achievement) || !stats.canUnlockAchievement(event.achievement)) return;
        bridge.server(
            event.entityPlayer.getCommandSenderName() + " earned ["
                + event.achievement.func_150951_e()
                    .getUnformattedText()
                + "].");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void death(LivingDeathEvent event) {
        if (!(event.entityLiving instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.entityLiving;
        if (!isPublic(player, false)) return;
        GameRules rules = player.worldObj.getGameRules();
        if (rules.hasRule("showDeathMessages") && !rules.getGameRuleBooleanValue("showDeathMessages")) return;
        bridge.server(
            player.func_110142_aN()
                .func_151521_b()
                .getUnformattedText());
    }

    @SubscribeEvent
    public void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isPublic(event.player, false)) return;
        bridge.server(event.player.getCommandSenderName() + " joined.");
    }

    @SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (serverUtilitiesEvents != null) serverUtilitiesEvents.left(event.player.getUniqueID());
        if (!isPublic(event.player, false)) return;
        bridge.server(event.player.getCommandSenderName() + " left.");
    }

    private boolean isPublic(EntityPlayer player, boolean chat) {
        return player instanceof EntityPlayerMP && !(player instanceof FakePlayer)
            && (serverUtilitiesEvents == null || serverUtilitiesEvents.visible(player, chat));
    }

    private final class LinkCommand extends CommandBase {

        private final boolean link;

        LinkCommand(boolean link) {
            this.link = link;
        }

        @Override
        public String getCommandName() {
            return link ? "link" : "unlink";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/" + getCommandName();
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return sender instanceof EntityPlayerMP;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (!(sender instanceof EntityPlayerMP) || args.length != 0) {
                sender.addChatMessage(new ChatComponentText("In-game usage: " + getCommandUsage(sender)));
                return;
            }
            EntityPlayerMP player = (EntityPlayerMP) sender;
            UUID id = player.getUniqueID();
            bridge.accountCommand(id, player.getCommandSenderName(), link, player::addChatMessage);
        }
    }
}
