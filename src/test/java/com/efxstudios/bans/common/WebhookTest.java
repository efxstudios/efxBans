package com.efxstudios.bans.common;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delivery and retry rules, against a local HTTP server standing in for Discord. */
class WebhookTest {
    private HttpServer server;
    private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private Webhook webhook;
    private URI url;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses.isEmpty() ? 204 : statuses.poll();
            if (status == 429) exchange.getResponseHeaders().add("Retry-After", "0.01");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        webhook = new Webhook(logs::add, 1);
    }

    @AfterEach
    void stop() {
        webhook.close();
        server.stop(0);
    }

    @Test
    void successIsSentOnce() {
        assertTrue(webhook.deliver(url, "{}"));
        assertEquals(1, requests.get());
        assertTrue(logs.isEmpty());
    }

    @Test
    void rateLimitAndServerErrorsAreRetried() {
        statuses.add(429);
        statuses.add(502);
        assertTrue(webhook.deliver(url, "{}"));
        assertEquals(3, requests.get());
    }

    @Test
    void clientErrorsAreNotRetried() {
        statuses.add(400);
        assertFalse(webhook.deliver(url, "{}"));
        assertEquals(1, requests.get());
        assertTrue(logs.getFirst().contains("400"));
    }

    @Test
    void givesUpAfterThreeAttempts() {
        statuses.add(500);
        statuses.add(500);
        statuses.add(500);
        assertFalse(webhook.deliver(url, "{}"));
        assertEquals(3, requests.get());
    }

    @Test
    void onlyDiscordWebhookUrlsAreAccepted() {
        for (String bad : new String[]{"http://discord.com/api/webhooks/1/a", "https://evil.example/api/webhooks/1/a",
                "https://discord.com.evil.example/api/webhooks/1/a", "https://discord.com/api/webhooks/1/a?x=1"})
            assertThrows(IllegalArgumentException.class, () -> new Webhook.Settings(true, bad, Set.of(), false, "t", "f"), bad);
        new Webhook.Settings(true, "https://discord.com/api/webhooks/123/abc_DEF-1", Set.of(), false, "t", "f");
        assertFalse(new Webhook.Settings(true, "https://discord.com/api/webhooks/123/secret", Set.of(), false, "t", "f").toString().contains("secret"));
    }
}
