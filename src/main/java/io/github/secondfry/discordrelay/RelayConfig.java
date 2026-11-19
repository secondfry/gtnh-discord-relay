package io.github.secondfry.discordrelay;

import java.nio.file.Path;

import net.minecraftforge.common.config.Configuration;

final class RelayConfig {

    final Path settingsFile;
    final Path linksFile;
    final String botToken;
    final String webhookUrl;
    final String inputChannelId;

    RelayConfig(Path directory) {
        settingsFile = directory.resolve("settings.cfg");
        Configuration settings = new Configuration(settingsFile.toFile());
        String category = Configuration.CATEGORY_GENERAL;
        botToken = settings.getString("botToken", category, "", "Required Discord bot token.")
            .trim();
        webhookUrl = settings
            .getString(
                "webhookUrl",
                category,
                "",
                "Required webhook URL. Format: https://discord.com/api/webhooks/<id>/<token>.")
            .trim();
        inputChannelId = settings
            .getString(
                "inputChannelId",
                category,
                "",
                "Optional Discord input text channel ID. Leave blank for outbound only.")
            .trim();
        linksFile = directory.resolve("links.properties");
        if (!settings.hasChanged()) return;
        settings.save();
    }

    boolean hasValues() {
        return !botToken.isEmpty() || !webhookUrl.isEmpty() || !inputChannelId.isEmpty();
    }

    boolean hasRequiredSettings() {
        return !botToken.isEmpty() && webhookUrl.matches("https://discord\\.com/api/webhooks/[0-9]{17,20}/[\\w-]+");
    }

    static boolean validChannelId(String channel) {
        if (!channel.matches("[1-9][0-9]{16,19}")) return false;
        try {
            Long.parseUnsignedLong(channel);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
