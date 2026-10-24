package com.efxstudios.bans.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Discord notifications. Delivery is best effort: a failure never undoes the punishment. */
public final class Webhook implements AutoCloseable {
    private static final int ATTEMPTS = 3;

    public record Settings(boolean enabled, String url, Set<String> actions, boolean includeIp, String title, String footer) {
        public Settings {
            actions = Set.copyOf(actions);
            // Only Discord webhook endpoints: a config value can't turn this into a request to an arbitrary host.
            if (enabled && !url.matches("https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("Invalid Discord webhook URL.");
        }

        /** The URL contains the webhook token. */
        @Override
        public String toString() {
            return "Webhook.Settings[enabled=" + enabled + ", actions=" + actions + "]";
        }
    }

    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), r -> {
        Thread thread = new Thread(r, "efxBans-discord");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Consumer<String> logger;
    private final long minimumRetryMillis;

    public Webhook(Consumer<String> logger) {
        this(logger, 1000);
    }

    Webhook(Consumer<String> logger, long minimumRetryMillis) {
        this.logger = logger;
        this.minimumRetryMillis = minimumRetryMillis;
    }

    public void send(Settings settings, Messages messages, String action, Punishment p, String staff, String changeReason) {
        if (!settings.enabled() || !settings.actions().contains(action)) return;
        String duration = !p.kind().timed() ? messages.text("none", "—")
                : p.expires() == 0 ? messages.text("permanent", "Permanent") : messages.duration(p.expires() - p.created());
        List<String> fields = new ArrayList<>();
        fields.add(field(messages.text("webhook.player", "Player"), p.name()));
        fields.add(field(messages.text("webhook.staff", "Staff"), staff));
        fields.add(field(messages.text("webhook.type", "Punishment"), p.kind().name()));
        fields.add(field(messages.text("webhook.duration", "Duration"), duration));
        fields.add(field(messages.text("webhook.reason", "Reason"), changeReason));
        if (settings.includeIp() && p.ip() != null) fields.add(field(messages.text("webhook.ip", "IP"), p.ip()));
        String json = "{\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"title\":" + quote(settings.title() + " • " + action)
                + ",\"color\":16733782"
                + ",\"fields\":[" + String.join(",", fields) + "]"
                + ",\"footer\":{\"text\":" + quote(settings.footer()) + "}"
                + ",\"timestamp\":" + quote(Instant.now().toString())
                + "}]}";
        try {
            queue.execute(() -> deliver(URI.create(settings.url()), json));
        } catch (RejectedExecutionException e) {
            logger.accept("Discord queue is full; notification not sent (#" + p.id() + "). The punishment is saved in the database.");
        }
    }

    /** Retries server errors and rate limits up to three times; other 4xx answers are final. */
    boolean deliver(URI url, String json) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return true;
                if (status != 429 && status < 500) {
                    logger.accept("Discord rejected the notification: HTTP " + status);
                    return false;
                }
                if (attempt == ATTEMPTS - 1) break;
                long delay = Math.min(15000, minimumRetryMillis * (attempt + 1));
                if (status == 429) delay = retryAfter(response.headers().firstValue("Retry-After").orElse("2"), delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                if (attempt == ATTEMPTS - 1) break;
            }
        }
        logger.accept("Discord notification failed after 3 attempts; the punishment is still saved.");
        return false;
    }

    /** Discord sends Retry-After in seconds (possibly fractional). */
    private long retryAfter(String header, long fallback) {
        try {
            return Math.min(15000, Math.max(minimumRetryMillis, (long) (Double.parseDouble(header) * 1000)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String field(String name, String value) {
        String text = value == null || value.isBlank() ? "—" : value.substring(0, Math.min(1000, value.length()));
        return "{\"name\":" + quote(name) + ",\"value\":" + quote(text) + ",\"inline\":true}";
    }

    /** JSON string literal; UUIDs are masked like in chat messages. */
    static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : Messages.hideUuid(value).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 32) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    @Override
    public void close() {
        queue.shutdownNow();
        http.close();
    }
}
