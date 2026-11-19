# Discord Relay for GTNH

Server-side mod for GT New Horizons 2.9.0 dedicated servers. Minecraft chat appears in Discord under the player's Minecraft username with their Minotar head. Achievements, BetterQuesting completions, deaths, joins, leaves, ServerUtilities AFK transitions, and server status use the webhook name **Minecraft Server**. Human text messages from the configured Discord channel appear in Minecraft. Bot messages, webhooks, Discord system messages, and attachment-only messages are ignored.

## Setup

1. Download the JAR from the [latest GitHub release](https://github.com/secondfry/gtnh-discord-relay/releases/latest) and put it in the dedicated server's `mods/` directory.
2. Create a webhook in the Discord channel where you want Minecraft posts and create a bot in the Discord Developer Portal. If you set an input channel ID, enable **Message Content Intent**, install the bot with the `bot` and `applications.commands` scopes, and grant it **View Channel** there. If the intent is disabled, Discord rejects the connection with code 4014 and webhook delivery also stops. The webhook may post to a different channel.

   If you set an input channel ID, invite the bot with this link, replacing `<APPLICATION_ID>` with the Application ID from Developer Portal → your app → General Information:

   ```
   https://discord.com/oauth2/authorize?client_id=<APPLICATION_ID>&scope=bot%20applications.commands&permissions=1024
   ```

   `permissions=1024` is View Channel only, which is all the relay needs: slash-command replies are ephemeral and outbound messages go through the webhook. If a channel override hides the channel from the bot's role, allow View Channel there. If the relay logs "cannot see the configured Discord text channel", the bot is not in the server, lacks View Channel, or `inputChannelId` is wrong or not a regular text channel.
3. Start the server once to generate `config/discordrelay/settings.cfg`. Fill in the `general` section and restart the server. Keep the token and webhook URL private.

   | Setting | Value |
   | --- | --- |
   | `botToken` | Required. Bot token |
   | `webhookUrl` | Required. `https://discord.com/api/webhooks/<id>/<token>` |
   | `inputChannelId` | Optional. Discord input text channel ID |

Run `/link` in Minecraft, then `/link code:<code>` in the relay channel. Codes are private, single-use and expire in five minutes. Run `/unlink` on either side. Messages marked `[Discord linked]` use the Minecraft name recorded at link time. Other Discord chat uses the sender's Discord display name. Links are stored in `config/discordrelay/links.properties`. Use an authenticated Minecraft server if players rely on the linked identity.

## Delivery

Discord I/O, account storage, and watchdog reporting run off the Minecraft thread. The server tick drains a bounded inbound queue. One outbound worker uses the bot's JDA instance to send webhook posts in queue order. JDA handles Discord rate limits, retries, and request failure logs. The JVM waits up to 20 seconds for the outbound queue to drain at shutdown. Posts, including the final notice, can be dropped when the queue is full or the JVM exits. Pings from webhook content are disabled.

If the JVM exits before Forge reports a final status, the relay queues “Minecraft JVM is shutting down.” Silent quests, events from fake players, and muted chat canceled by ServerUtilities are skipped. Vanished players’ events are hidden, with public chat relayed only if ServerUtilities allows it.

With blank settings the mod stays idle. Outbound delivery requires a valid webhook URL and successful bot initialization. Transient login failures retry every 30 seconds until shutdown. If the input channel ID is blank or malformed, only outbound delivery starts. If the ID is well-formed but the bot cannot see that channel, the relay logs the failed lookup and `/link` refuses to issue codes. A terminal bot shutdown also stops webhook delivery. If Discord rejects an unauthorized or deleted webhook, outbound delivery stays disabled until restart. Forge captures JDA’s diagnostics under `STDERR` in the server log.

The watchdog monitors the server loop during normal gameplay and ServerUtilities' pause-when-empty mode. It alerts after 120 seconds during world startup and 30 seconds while running or shutting down. Crash alerts cover unexpected exits through Forge's server lifecycle. A hard kill, host outage, JVM fatal error, or crash before the relay initializes requires an external monitor.

BetterQuesting and ServerUtilities hooks were built against the GTNH 2.9.0 versions. See [event sources](docs/event-sources.md).

The relay source is [MIT-licensed](LICENSE.md). Bundled JDA dependencies retain their own licenses and notices in the JAR, including [GNU Trove](src/main/resources/META-INF/discordrelay-licenses/Trove.txt).
