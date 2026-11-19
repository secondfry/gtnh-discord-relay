package io.github.secondfry.discordrelay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import net.dv8tion.jda.api.JDA;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

final class DiscordBridge {

    private static final String BUSY = "Discord relay is busy or stopping. Try again later.";
    private final BlockingQueue<Post> outgoing = new ArrayBlockingQueue<>(256);
    private final BlockingQueue<ChatComponentText> incoming = new ArrayBlockingQueue<>(128);
    private final BlockingQueue<Runnable> replies = new ArrayBlockingQueue<>(128);
    private final AtomicInteger dropped = new AtomicInteger();
    private final CountDownLatch stopping = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ThreadPoolExecutor accountWorker;
    private final String inputChannelId;
    private final ServerHeartbeat heartbeat;
    private final Thread shutdownHook;
    private volatile AccountLinks links;
    private volatile JDA session;
    private volatile boolean closed;

    DiscordBridge(RelayConfig config, ServerHeartbeat heartbeat) {
        this.heartbeat = heartbeat;
        if (!config.hasRequiredSettings()) {
            if (config.hasValues()) {
                DiscordRelay.LOG
                    .error("Discord Relay requires botToken and a valid webhookUrl in {}.", config.settingsFile);
            }
            inputChannelId = null;
            accountWorker = null;
            shutdownHook = null;
            return;
        }
        inputChannelId = RelayConfig.validChannelId(config.inputChannelId) ? config.inputChannelId : null;
        if (inputChannelId == null) {
            DiscordRelay.LOG.warn(
                "Discord chat and slash commands need a valid input channel ID. Starting outbound delivery only.");
        }
        accountWorker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            task -> daemon("Discord Relay accounts", task));
        accountWorker.execute(() -> loadAccountLinks(config.linksFile));
        shutdownHook = daemon("Discord Relay shutdown", this::onJvmShutdown);
        Runtime.getRuntime()
            .addShutdownHook(shutdownHook);
        daemon("Discord Relay outbound", () -> runDiscord(config.botToken, config.webhookUrl)).start();
    }

    boolean isConfigured() {
        return shutdownHook != null;
    }

    void tick(MinecraftServer server) {
        for (int i = 0; i < 16; i++) {
            Runnable reply = replies.poll();
            if (reply != null) reply.run();
            ChatComponentText message = incoming.poll();
            if (message != null) server.getConfigurationManager()
                .sendChatMsg(message);
            if (message == null && reply == null) break;
        }
    }

    void player(String name, String text) {
        offer(name, text);
    }

    void server(String text) {
        offer(null, text);
    }

    private void offer(String player, String text) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain.trim()
            .isEmpty()) return;
        offer(new Post(player, plain));
    }

    private synchronized void offer(Post post) {
        if (!closed && !outgoing.offer(post)) dropped.incrementAndGet();
    }

    synchronized void close(String status) {
        if (closed) return;
        offer(null, status);
        closed = true;
        stopping.countDown();
        accountWorker.shutdown();
    }

    private void onJvmShutdown() {
        close("Minecraft JVM is shutting down.");
        try {
            finished.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread()
                .interrupt();
        }
    }

    private void runDiscord(String token, String webhookUrl) {
        try {
            JDA session = new DiscordBot(this, inputChannelId, stopping).connect(token);
            if (session == null) return;
            this.session = session;
            try {
                drainOutgoing(new DiscordWebhook(session, webhookUrl));
            } finally {
                this.session = null;
                session.shutdownNow();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread()
                .interrupt();
        } catch (RuntimeException | LinkageError failure) {
            DiscordRelay.LOG.error("Discord relay worker stopped", failure);
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime()
                    .removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already running its shutdown hooks.
            }
        }
    }

    private void drainOutgoing(DiscordWebhook webhook) throws InterruptedException {
        ServerHeartbeat.Phase lastStall = null;
        while (!closed || !outgoing.isEmpty()) {
            if (!closed) {
                ServerHeartbeat.Phase stall = heartbeat.stalled(System.nanoTime());
                if (stall != lastStall) {
                    lastStall = stall;
                    server(stall == null ? "Minecraft server progress resumed." : stall.alert);
                }
            }
            int lost = dropped.getAndSet(0);
            if (lost != 0) DiscordRelay.LOG.warn("Discord Relay queues dropped {} messages", lost);
            Post post = outgoing.poll(1, TimeUnit.SECONDS);
            if (post == null) continue;
            webhook.send(post.player, post.text);
        }
    }

    void acceptDiscordMessage(String discordId, String displayName, String text) {
        String content = clean(text);
        if (content.isEmpty()) return;
        AccountLinks current = links;
        String linkedName = current == null ? null : current.nameFor(discordId);
        String name = linkedName == null ? clean(displayName) : linkedName;
        String source = linkedName == null ? "[Discord]" : "[Discord linked]";
        ChatComponentText line = new ChatComponentText(source + " <" + name + "> " + content);
        if (!incoming.offer(line)) dropped.incrementAndGet();
    }

    void accountCommand(UUID player, String name, boolean link, Consumer<ChatComponentText> reply) {
        if (link && !canLink()) {
            reply.accept(
                new ChatComponentText(
                    "Account linking is unavailable because the Discord bot cannot access the input channel."));
            return;
        }
        accountTask(
            links -> link ? links.issue(player, name) : links.unlinkMinecraft(player),
            response -> {
                if (!replies.offer(() -> reply.accept(new ChatComponentText(response)))) dropped.incrementAndGet();
            });
    }

    private boolean canLink() {
        JDA current = session;
        return !closed && current != null
            && current.getStatus() == JDA.Status.CONNECTED
            && DiscordBot.findInputChannel(current, inputChannelId) != null;
    }

    private void loadAccountLinks(Path linkFile) {
        try {
            links = new AccountLinks(linkFile);
        } catch (IOException | IllegalArgumentException failure) {
            DiscordRelay.LOG.error("Discord Relay could not load the account link file", failure);
        }
    }

    void accountTask(Function<AccountLinks, String> task, Consumer<String> reply) {
        try {
            accountWorker.execute(() -> {
                AccountLinks current = links;
                reply.accept(current == null ? "Account linking is unavailable." : task.apply(current));
            });
        } catch (RejectedExecutionException busy) {
            reply.accept(BUSY);
        }
    }

    private static String clean(String content) {
        return content.replaceAll("[\\p{Cntrl}§]", " ")
            .trim();
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Post {

        final String player;
        final String text;

        Post(String player, String text) {
            this.player = player;
            this.text = text;
        }
    }
}
