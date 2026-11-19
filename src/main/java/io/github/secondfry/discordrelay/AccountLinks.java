package io.github.secondfry.discordrelay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class AccountLinks {

    private static final long CODE_LIFETIME = TimeUnit.MINUTES.toNanos(5);
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String SAVE_FAILED = "Could not save the account link.";

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingCode> codes = new HashMap<>();
    // The account worker owns mutations. Gateway listeners read the published snapshot.
    private volatile Properties saved = new Properties();

    AccountLinks(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) return;
        try (InputStream input = Files.newInputStream(file)) {
            saved.load(input);
        }
        Set<UUID> owners = new HashSet<>();
        for (String discord : saved.stringPropertyNames()) {
            String[] link = saved.getProperty(discord)
                .split(":", 2);
            if (!discord.matches("[0-9]{17,20}") || link.length != 2
                || !MINECRAFT_NAME.matcher(link[1])
                    .matches()) {
                throw new IOException("Malformed account link");
            }
            UUID owner;
            try {
                owner = UUID.fromString(link[0]);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Malformed Minecraft UUID in account link");
            }
            if (!owner.toString()
                .equals(link[0])) throw new IOException("Noncanonical Minecraft UUID in account link");
            if (!owners.add(owner)) throw new IOException("Duplicate Minecraft UUID in account links");
        }
    }

    String nameFor(String discord) {
        String linked = saved.getProperty(discord);
        return linked == null ? null : linked.substring(37);
    }

    String issue(UUID player, String name) {
        if (discordFor(player) != null) return "Already linked. Use /unlink first.";
        if (!MINECRAFT_NAME.matcher(name)
            .matches()) return "This Minecraft username cannot be linked.";
        clearCodes(player);
        String code;
        do {
            code = Long.toHexString(random.nextLong());
        } while (codes.containsKey(code));
        codes.put(code, new PendingCode(player, name, System.nanoTime()));
        return "In the Discord relay channel, use /link code:" + code + " within 5 minutes. Keep this code private.";
    }

    String redeem(String discord, String code) {
        clearCodes(null);
        String normalizedCode = code.trim()
            .toLowerCase(Locale.ROOT);
        PendingCode pending = codes.get(normalizedCode);
        if (pending == null) return "Invalid or expired code. Use /link in Minecraft.";
        if (saved.containsKey(discord) || discordFor(pending.player) != null) {
            return "One of these accounts is already linked. Use /unlink first.";
        }
        if (!save(discord, pending.player + ":" + pending.name)) return SAVE_FAILED;
        codes.remove(normalizedCode);
        return "Linked to " + pending.name + ".";
    }

    String unlinkMinecraft(UUID player) {
        clearCodes(player);
        return unlinkDiscord(discordFor(player));
    }

    String unlinkDiscord(String discord) {
        if (discord == null || !saved.containsKey(discord)) return "No linked account.";
        return save(discord, null) ? "Accounts unlinked." : SAVE_FAILED;
    }

    private void clearCodes(UUID player) {
        long now = System.nanoTime();
        codes.values()
            .removeIf(code -> code.player.equals(player) || now - code.issued >= CODE_LIFETIME);
    }

    private String discordFor(UUID player) {
        String prefix = player + ":";
        for (String discord : saved.stringPropertyNames()) {
            if (saved.getProperty(discord)
                .startsWith(prefix)) return discord;
        }
        return null;
    }

    private boolean save(String discord, String link) {
        Properties updated = new Properties();
        updated.putAll(saved);
        if (link == null) updated.remove(discord);
        else updated.setProperty(discord, link);
        Path temp = null;
        try {
            Path dir = file.toAbsolutePath()
                .getParent();
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "discordrelay-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                updated.store(output, "Discord user ID = Minecraft UUID and username");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            saved = updated;
            return true;
        } catch (IOException failure) {
            DiscordRelay.LOG.error("Discord Relay could not save the account link file", failure);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException failure) {
                    DiscordRelay.LOG.warn("Discord Relay could not remove its temporary link file", failure);
                }
            }
        }
    }

    private static final class PendingCode {

        final UUID player;
        final String name;
        final long issued;

        PendingCode(UUID player, String name, long issued) {
            this.player = player;
            this.name = name;
            this.issued = issued;
        }
    }
}
