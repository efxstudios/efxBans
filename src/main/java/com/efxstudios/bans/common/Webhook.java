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
        String json = payload(settings, messages, action, p, staff, changeReason, Instant.now());
        try {
            queue.execute(() -> deliver(URI.create(settings.url()), json));
        } catch (RejectedExecutionException e) {
            logger.accept("Discord queue is full; notification not sent (#" + p.id() + "). The punishment is saved in the database.");
        }
    }

    /** Pure payload builder, also used to preview notifications without sending anything to Discord. */
    static String payload(Settings settings, Messages messages, String action, Punishment p, String staff, String changeReason, Instant now) {
        String event = action.equals("remove") ? (p.kind() == Kind.MUTE ? "unmute" : "unban") : action;
        String label = messages.text("webhook.actions." + event, event);
        String type = messages.text("types." + p.kind().key(), p.kind().key());
        String duration = !p.kind().timed() ? messages.text("none", "—")
                : p.expires() == 0 ? messages.text("permanent", "Permanent") : messages.duration(p.expires() - p.created());
        List<String> fields = new ArrayList<>();
        fields.add(field(messages.text("webhook.player", "Player"), literal(p.name()), true));
        fields.add(field(messages.text("webhook.staff", "Staff"), literal(staff), true));
        fields.add(field(messages.text("webhook.type", "Punishment"), literal(type), true));
        if (p.kind().timed()) {
            fields.add(field(messages.text("webhook.duration", "Duration"), literal(duration), true));
            String expires = p.expires() == 0 ? literal(messages.text("permanent", "Permanent"))
                    : "<t:" + p.expires() / 1000 + ":f>\n<t:" + p.expires() / 1000 + ":R>";
            fields.add(field(messages.text("webhook.expires", "Expires"), expires, true));
        }
        String reason = changeReason == null || changeReason.isBlank() ? p.reason() : changeReason;
        fields.add(field(messages.text("webhook.reason", "Reason"), literal(reason), false));
        if (settings.includeIp() && p.ip() != null) fields.add(field(messages.text("webhook.ip", "IP"), literal(p.ip()), true));
        String head = headIdentifier(p);
        boolean showHead = head != null;
        String author = "{\"name\":" + quote(limit(p.name(), 128))
                + (showHead ? ",\"icon_url\":" + jsonString("https://mc-heads.net/avatar/" + head + "/64") : "") + "}";
        return "{\"username\":" + quote(limit(settings.title(), 80)) + ",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"author\":" + author
                + ",\"title\":" + quote(limit(settings.title() + " • " + label, 256))
                + ",\"color\":" + color(action)
                + (showHead ? ",\"thumbnail\":{\"url\":" + jsonString("https://mc-heads.net/head/" + head + "/128") + "}" : "")
                + ",\"fields\":[" + String.join(",", fields) + "]"
                + ",\"footer\":{\"text\":" + quote(limit(settings.footer(), 480) + " • #" + p.id()) + "}"
                + ",\"timestamp\":" + quote(now.toString())
                + "}]}";
    }

    private static int color(String action) {
        return switch (action) {
            case "ban", "ipban" -> 0xED4245;
            case "mute" -> 0xFAA61A;
            case "kick" -> 0xFEE75C;
            case "warn" -> 0x5865F2;
            case "remove" -> 0x57F287;
            case "undo" -> 0x9B59B6;
            default -> 0x5865F2;
        };
    }

    private static String headIdentifier(Punishment p) {
        if (p.target() == null) return null; // Address-only bans have no player head.
        // Offline-mode UUIDs do not identify Mojang skins; use a validated account name in that case.
        if (p.target().version() == 3 && p.name().matches("[A-Za-z0-9_]{1,16}")) return p.name();
        return p.target().toString();
    }

    private static String literal(String value) {
        if (value == null) return "—";
        StringBuilder text = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ("\\`*_~|>[]".indexOf(c) >= 0) text.append('\\');
            text.append(c);
        }
        return text.toString();
    }

    private static String limit(String value, int maximum) {
        if (value == null || value.isBlank()) return "—";
        int end = Math.min(maximum, value.length());
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
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

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":" + quote(limit(name, 80)) + ",\"value\":" + quote(limit(value, inline ? 256 : 1000)) + ",\"inline\":" + inline + "}";
    }

    /** JSON string literal; UUIDs are masked like in chat messages. */
    static String quote(String value) {
        return jsonString(Messages.hideUuid(value));
    }

    /** Controlled image URLs must retain their UUID; display text still goes through quote(). */
    private static String jsonString(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
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
