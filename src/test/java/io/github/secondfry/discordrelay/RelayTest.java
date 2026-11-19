package io.github.secondfry.discordrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.exceptions.InvalidTokenException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class RelayTest {

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void linksAreSingleUseAndPersistent() throws IOException {
        Path file = directory.getRoot()
            .toPath()
            .resolve("links.properties");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        String first = "123456789012345678";
        String second = "123456789012345679";
        AccountLinks links = new AccountLinks(file);
        String stale = code(links.issue(steve, "Steve"));
        String active = code(links.issue(steve, "Steve"));
        assertTrue(
            links.redeem(first, stale)
                .startsWith("Invalid"));
        assertEquals("Linked to Steve.", links.redeem(first, " " + active + " "));
        assertTrue(
            links.redeem(second, active)
                .startsWith("Invalid"));
        assertTrue(
            links.issue(steve, "Steve")
                .startsWith("Already linked"));
        String alexCode = code(links.issue(alex, "Alex"));
        assertTrue(
            links.redeem(first, alexCode)
                .contains("already linked"));
        assertEquals("Linked to Alex.", links.redeem(second, alexCode));
        links = new AccountLinks(file);
        assertEquals("Steve", links.nameFor(first));
        assertEquals("Accounts unlinked.", links.unlinkMinecraft(steve));
        assertEquals("Accounts unlinked.", links.unlinkDiscord(second));
        links = new AccountLinks(file);
        assertNull(links.nameFor(first));
        assertNull(links.nameFor(second));
    }

    @Test
    public void rejectsDuplicateAndMalformedMinecraftIds() throws IOException {
        Path file = directory.newFile()
            .toPath();
        UUID id = UUID.randomUUID();
        String first = "123456789012345678";
        String second = "123456789012345679";
        String duplicate = first + "=" + id + ":Steve\n" + second + "=" + id + ":Alex\n";
        Files.write(file, duplicate.getBytes(StandardCharsets.UTF_8));
        assertTrue(
            assertThrows(IOException.class, () -> new AccountLinks(file)).getMessage()
                .startsWith("Duplicate Minecraft UUID"));
        String malformed = first + "=-------------------------------12345:Steve\n";
        Files.write(file, malformed.getBytes(StandardCharsets.UTF_8));
        assertTrue(
            assertThrows(IOException.class, () -> new AccountLinks(file)).getMessage()
                .startsWith("Malformed Minecraft UUID"));
    }

    @Test
    public void heartbeatTracksStartupRunningAndShutdown() {
        long second = TimeUnit.SECONDS.toNanos(1);
        ServerHeartbeat heartbeat = new ServerHeartbeat(0);
        assertNull(heartbeat.stalled(119 * second));
        assertEquals(ServerHeartbeat.Phase.STARTING, heartbeat.stalled(120 * second));
        heartbeat.enter(ServerHeartbeat.Phase.RUNNING, 120 * second);
        for (int time = 121; time < 180; time++) {
            heartbeat.progress(time * second);
            assertNull(heartbeat.stalled(time * second));
        }
        assertEquals(ServerHeartbeat.Phase.RUNNING, heartbeat.stalled(209 * second));
        heartbeat.enter(ServerHeartbeat.Phase.STOPPING, 210 * second);
        assertNull(heartbeat.stalled(239 * second));
        assertEquals(ServerHeartbeat.Phase.STOPPING, heartbeat.stalled(240 * second));
    }

    @Test
    public void retriesConnectionFailuresButNotBadTokens() {
        assertTrue(DiscordBot.isTransient(new IllegalStateException(new IOException("connection reset"))));
        assertFalse(DiscordBot.isTransient(new InvalidTokenException()));
    }

    @Test
    public void generatesBlankSettingsAndLoadsEditedValues() throws Exception {
        Field minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        Object previous = minecraftHome.get(null);
        minecraftHome.set(null, directory.getRoot());
        try {
            checkSettingsFile();
        } finally {
            minecraftHome.set(null, previous);
        }
    }

    private void checkSettingsFile() throws IOException {
        Path configDirectory = directory.getRoot()
            .toPath()
            .resolve("config/discordrelay");
        Path settingsFile = configDirectory.resolve("settings.cfg");
        RelayConfig blank = new RelayConfig(configDirectory);
        assertTrue(Files.isRegularFile(settingsFile));
        assertFalse(blank.hasValues());
        assertFalse(blank.hasRequiredSettings());
        assertEquals(configDirectory.resolve("links.properties"), blank.linksFile);
        assertFalse(Files.exists(blank.linksFile));

        String webhook = "https://discord.com/api/webhooks/123456789012345678/mock_token";
        String values = "general {\n    S:botToken= test-bot-token  \n    S:webhookUrl=" + webhook
            + "\n    S:inputChannelId= 123456789012345679\n}\n";
        Files.write(settingsFile, values.getBytes(StandardCharsets.UTF_8));
        RelayConfig configured = new RelayConfig(configDirectory);
        assertTrue(configured.hasRequiredSettings());
        assertEquals("test-bot-token", configured.botToken);
        assertEquals(webhook, configured.webhookUrl);
        assertEquals("123456789012345679", configured.inputChannelId);
        assertEquals(values, new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8));
    }

    @Test
    public void validatesUnsignedDiscordChannelIds() {
        assertTrue(RelayConfig.validChannelId("123456789012345678"));
        assertTrue(RelayConfig.validChannelId("18446744073709551615"));
        assertFalse(RelayConfig.validChannelId("18446744073709551616"));
        assertFalse(RelayConfig.validChannelId("-123456789012345678"));
        assertFalse(RelayConfig.validChannelId(""));
    }

    private static String code(String reply) {
        return reply.split("code:", 2)[1].split(" ", 2)[0];
    }
}
