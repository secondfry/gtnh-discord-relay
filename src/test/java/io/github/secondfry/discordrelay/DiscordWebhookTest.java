package io.github.secondfry.discordrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.RestConfig;
import net.dv8tion.jda.api.utils.SessionController;
import net.dv8tion.jda.api.utils.SessionControllerAdapter;
import net.dv8tion.jda.api.utils.data.DataObject;

import org.junit.After;
import org.junit.Test;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public class DiscordWebhookTest {

    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/mock_token";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String MESSAGE = "{\"id\":\"123456789012345679\",\"channel_id\":\"123456789012345680\","
        + "\"type\":0,\"webhook_id\":\"123456789012345678\",\"mentions\":[],\"mention_roles\":[],"
        + "\"author\":{\"id\":\"123456789012345678\",\"username\":\"Webhook\",\"discriminator\":\"0000\",\"bot\":true}}";

    private JDA session;

    @After
    public void stopDiscord() {
        if (session == null) return;
        session.shutdownNow();
    }

    @Test(timeout = 15000)
    public void jdaWaitsAndRetriesRateLimitedWebhookPosts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Request> sent = new AtomicReference<>();
        AtomicReference<DataObject> captured = new AtomicReference<>();
        DiscordWebhook webhook = webhook(chain -> {
            Request request = chain.request();
            sent.set(request);
            Buffer buffer = new Buffer();
            request.body()
                .writeTo(buffer);
            captured.set(DataObject.fromJson(buffer.readUtf8()));
            if (attempts.incrementAndGet() == 1) {
                return response(request, 429, "{\"retry_after\":1}").newBuilder()
                    .header("Retry-After", "1")
                    .header("Via", "1.1 discord")
                    .header("X-RateLimit-Bucket", "test-webhook")
                    .build();
            }
            return response(request, 200, MESSAGE);
        });

        long started = System.nanoTime();
        assertTrue(webhook.send("Steve", "Hello @everyone"));
        assertEquals(2, attempts.get());
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));
        assertEquals(
            RestConfig.USER_AGENT,
            sent.get()
                .header("User-Agent"));
        assertEquals(
            "true",
            sent.get()
                .url()
                .queryParameter("wait"));
        DataObject payload = captured.get();
        assertEquals("Hello @everyone", payload.getString("content"));
        assertEquals("Steve", payload.getString("username"));
        assertEquals("https://minotar.net/helm/Steve/64", payload.getString("avatar_url"));
        assertTrue(
            payload.getObject("allowed_mentions")
                .getArray("parse")
                .isEmpty());

        assertTrue(webhook.send(null, "Server started."));
        assertEquals(
            "Minecraft Server",
            captured.get()
                .getString("username"));
        assertFalse(
            captured.get()
                .hasKey("avatar_url"));
    }

    @Test(timeout = 10000)
    public void stopsSendingAfterDeletedWebhook() throws Exception {
        assertPermanentRejection(404, 10015);
    }

    @Test(timeout = 10000)
    public void stopsSendingAfterUnauthorizedWebhook() throws Exception {
        assertPermanentRejection(401, 50027);
    }

    private void assertPermanentRejection(int status, int code) throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        DiscordWebhook webhook = webhook(chain -> {
            attempts.incrementAndGet();
            return response(chain.request(), status, "{\"code\":" + code + ",\"message\":\"Rejected\"}");
        });
        assertFalse(webhook.send("Steve", "First message"));
        assertFalse(webhook.send(null, "Server stopped."));
        assertEquals(1, attempts.get());
    }

    @Test(timeout = 10000)
    public void sendsLaterPostsAfterAnUnexpectedFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        DiscordWebhook webhook = webhook(chain -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Unexpected HTTP client failure");
            return response(chain.request(), 200, MESSAGE);
        });
        assertFalse(webhook.send("Steve", "First message"));
        assertTrue(webhook.send(null, "Server loop stalled."));
        assertTrue(webhook.send(null, "Server stopped."));
        assertEquals(3, attempts.get());
    }

    private DiscordWebhook webhook(Interceptor webhookRequests) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String path = request.url()
                .encodedPath();
            if (path.endsWith("/gateway/bot")) {
                return response(request, 200, "{\"url\":\"wss://127.0.0.1:1\",\"shards\":1}");
            }
            if (path.endsWith("/users/@me")) {
                return response(
                    request,
                    200,
                    "{\"id\":\"111111111111111111\",\"username\":\"Test\",\"discriminator\":\"0000\",\"bot\":true}");
            }
            if (path.endsWith("/webhooks/123456789012345678/mock_token")) return webhookRequests.intercept(chain);
            throw new IOException("Unexpected mock endpoint: " + path);
        })
            .build();
        session = JDABuilder.createLight("dummy", Collections.emptySet())
            .setSessionController(new SessionControllerAdapter() {

                @Override
                public void appendSession(SessionController.SessionConnectNode node) {
                    // The test uses REST only.
                }
            })
            .setHttpClient(http)
            .setEnableShutdownHook(false)
            .build();
        return new DiscordWebhook(session, URL);
    }

    private static Response response(Request request, int status, String body) {
        return new Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Mock")
            .header("Content-Type", "application/json")
            .body(ResponseBody.create(body, JSON))
            .build();
    }
}
