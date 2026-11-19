package io.github.secondfry.discordrelay;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

final class DiscordBot extends ListenerAdapter {

    private static final long LOGIN_RETRY_SECONDS = 30;

    private final DiscordBridge bridge;
    private final String channelId;
    private final CountDownLatch stopping;

    DiscordBot(DiscordBridge bridge, String channelId, CountDownLatch stopping) {
        this.bridge = bridge;
        this.channelId = channelId;
        this.stopping = stopping;
    }

    private boolean isStopping() {
        return stopping.getCount() == 0;
    }

    JDA connect(String token) throws InterruptedException {
        EnumSet<GatewayIntent> intents = channelId == null ? EnumSet.noneOf(GatewayIntent.class)
            : EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT);
        return connect(
            () -> JDABuilder.createLight(token, intents)
                .setEnableShutdownHook(false)
                .addEventListeners(this)
                .build());
    }

    JDA connect(Supplier<JDA> login) throws InterruptedException {
        while (!isStopping()) {
            try {
                return login.get();
            } catch (RuntimeException failure) {
                if (!isTransient(failure)) throw failure;
                if (isStopping()) return null;
                DiscordRelay.LOG.warn("Discord login failed. Retrying in {} seconds.", LOGIN_RETRY_SECONDS);
                if (stopping.await(LOGIN_RETRY_SECONDS, TimeUnit.SECONDS)) return null;
            }
        }
        return null;
    }

    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof TimeoutException
                || cause instanceof RateLimitedException) {
                return true;
            }
            if (cause instanceof ErrorResponseException) {
                int status = ((ErrorResponseException) cause).getResponse().code;
                return status < 0 || status == 429 || status >= 500;
            }
        }
        return false;
    }

    @Override
    public void onReady(ReadyEvent event) {
        if (channelId == null || isStopping()) return;
        GuildMessageChannel target = findInputChannel(event.getJDA(), channelId);
        if (target == null) {
            DiscordRelay.LOG.error("Discord Relay cannot see the configured Discord text channel.");
            return;
        }
        Guild guild = target.getGuild();
        register(
            guild,
            Commands.slash("link", "Link your Minecraft account")
                .addOption(OptionType.STRING, "code", "Code from /link in Minecraft", true));
        register(guild, Commands.slash("unlink", "Unlink your Minecraft account"));
    }

    static GuildMessageChannel findInputChannel(JDA session, String channelId) {
        if (channelId == null) return null;
        GuildMessageChannel channel = session.getTextChannelById(channelId);
        if (channel == null) return null;
        return channel.getGuild()
            .getSelfMember()
            .hasAccess(channel) ? channel : null;
    }

    private void register(Guild guild, CommandData command) {
        if (isStopping()) return;
        guild.upsertCommand(command)
            .queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (isStopping() || !event.getChannel()
            .getId()
            .equals(channelId)) return;
        Message message = event.getMessage();
        User author = event.getAuthor();
        if (author.isBot() || message.isWebhookMessage()
            || message.getType()
                .isSystem())
            return;

        Member member = event.getMember();
        String displayName = member == null ? author.getName() : member.getEffectiveName();
        bridge.acceptDiscordMessage(author.getId(), displayName, message.getContentDisplay());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (channelId == null || isStopping()) return;
        String command = event.getName();
        boolean link = command.equals("link");
        if (!link && !command.equals("unlink")) return;
        if (!channelId.equals(
            event.getChannel()
                .getId())) {
            event.reply("Use this command in the Minecraft relay channel.")
                .setEphemeral(true)
                .queue();
            return;
        }
        event.deferReply(true)
            .queue(hook -> {
                String discordId = event.getUser()
                    .getId();
                String code = event.getOption("code", "", option -> option.getAsString());
                bridge.accountTask(
                    links -> link ? links.redeem(discordId, code) : links.unlinkDiscord(discordId),
                    response -> hook.editOriginal(response)
                        .queue());
            });
    }
}
