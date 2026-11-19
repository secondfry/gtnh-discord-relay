package io.github.secondfry.discordrelay;

import java.util.Collections;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;

final class DiscordWebhook {

    private static final String SERVER_NAME = "Minecraft Server";

    private final IncomingWebhookClient client;
    private boolean disabled;

    DiscordWebhook(JDA session, String url) {
        client = WebhookClient.createClient(session, url);
    }

    boolean send(String player, String text) {
        if (disabled) return false;
        try {
            WebhookMessageCreateAction<Message> request = client.sendMessage(truncate(text))
                .setUsername(player == null ? SERVER_NAME : player.replaceAll("(?i)cly(?=de)|dis(?=cord)", "$0\u200B"))
                .setAllowedMentions(Collections.emptySet());
            if (player != null) request.setAvatarUrl("https://minotar.net/helm/" + player + "/64");
            request.complete();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            handleFailure(failure);
        }
        return false;
    }

    private void handleFailure(Throwable failure) {
        Response response = failure instanceof ErrorResponseException ? ((ErrorResponseException) failure).getResponse()
            : null;
        // JDA's requester already logs the exception that produced this response.
        if (response != null && response.getException() != null) return;
        int status = response == null ? 0 : response.code;
        if (status == 401 || status == 404) {
            disabled = true;
            DiscordRelay.LOG.error("Discord webhook rejected (HTTP {}). Replace the webhook URL and restart.", status);
            return;
        }
        RestAction.getDefaultFailure()
            .accept(failure);
    }

    private static String truncate(String text) {
        if (text.length() <= Message.MAX_CONTENT_LENGTH) return text;
        int end = Message.MAX_CONTENT_LENGTH - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\u2026";
    }
}
