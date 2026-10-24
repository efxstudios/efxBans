package com.efxstudios.bans.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebhookPayloadTest {
    private static final Instant NOW = Instant.parse("2026-10-24T17:00:00Z");
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private final Messages messages = new Messages("", Map.of("webhook.actions.ban", "Oyuncu yasaklandı",
            "webhook.actions.unban", "Yasak kaldırıldı", "webhook.actions.unmute", "Susturma kaldırıldı",
            "webhook.actions.undo", "Ceza geri alındı", "types.ban", "Yasak", "status.REMOVED", "Kaldırıldı"), "UTC");

    private Webhook.Settings settings(boolean ip) {
        return new Webhook.Settings(true, "https://discord.com/api/webhooks/123/placeholder", Set.of("ban", "remove", "undo"),
                ip, "efxBans", "Punishment log");
    }

    private Punishment punishment(Kind kind, UUID uuid, String name, String state, long expires) {
        return new Punishment(128, kind, uuid, name, "192.0.2.10", "CONSOLE", "Staff", "Original reason", NOW.toEpochMilli(), expires,
                state, 0, null, null, null, false);
    }

    private JsonObject payload(Webhook.Settings settings, String action, Punishment p, String reason) {
        return JsonParser.parseString(Webhook.payload(settings, messages, action, p, "Staff", reason, NOW)).getAsJsonObject();
    }

    private JsonObject embed(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private JsonObject field(JsonObject embed, String name) {
        for (var field : embed.getAsJsonArray("fields"))
            if (field.getAsJsonObject().get("name").getAsString().equals(name)) return field.getAsJsonObject();
        throw new AssertionError("Missing field: " + name);
    }

    @Test
    void banShowsRealHeadLocalizedActionAndDatesWithoutPingsOrIpLeak() {
        long expires = NOW.plusSeconds(604800).toEpochMilli();
        var payload = payload(settings(false), "ban", punishment(Kind.BAN, PLAYER, "Notch", "ACTIVE", expires), "@everyone **test**");
        var embed = embed(payload);
        assertEquals("efxBans • Oyuncu yasaklandı", embed.get("title").getAsString());
        assertEquals("Notch", embed.getAsJsonObject("author").get("name").getAsString());
        assertEquals("https://mc-heads.net/head/" + PLAYER + "/128", embed.getAsJsonObject("thumbnail").get("url").getAsString());
        assertTrue(embed.getAsJsonObject("author").get("icon_url").getAsString().contains(PLAYER.toString()));
        assertEquals(0xED4245, embed.get("color").getAsInt());
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertFalse(payload.has("content"));
        assertFalse(payload.toString().contains("192.0.2.10"));
        assertEquals("@everyone \\*\\*test\\*\\*", field(embed, "Reason").get("value").getAsString());
        assertFalse(field(embed, "Reason").get("inline").getAsBoolean());
        assertTrue(field(embed, "Expires").get("value").getAsString().contains("<t:" + expires / 1000 + ":R>"));
        assertEquals("Yasak", field(embed, "Punishment").get("value").getAsString());
        assertTrue(embed.getAsJsonObject("footer").get("text").getAsString().endsWith("#128"));
        assertEquals(NOW.toString(), embed.get("timestamp").getAsString());
    }

    @Test
    void playerHeadsAreAutomaticAndAddressOnlyBansNeverUseAnAvatar() {
        var address = embed(payload(settings(true), "ipban", punishment(Kind.IPBAN, null, "IP target", "ACTIVE", 0), "Reason"));
        assertFalse(address.has("thumbnail"));
        assertFalse(address.getAsJsonObject("author").has("icon_url"));
        assertEquals("192.0.2.10", field(address, "IP").get("value").getAsString());
        UUID offline = UUID.fromString("31bb5289-cb29-357e-a76b-cc3b2628b2f4");
        var named = embed(payload(settings(false), "ban", punishment(Kind.BAN, offline, "OfflinePlayer", "ACTIVE", 0), "Reason"));
        assertEquals("https://mc-heads.net/head/OfflinePlayer/128", named.getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    void removalsAndUndoAreDistinctAndKeepTheOriginalReason() {
        var ban = embed(payload(settings(false), "remove", punishment(Kind.BAN, PLAYER, "Notch", "REMOVED", 0), ""));
        assertTrue(ban.get("title").getAsString().endsWith("Yasak kaldırıldı"));
        assertEquals("Original reason", field(ban, "Reason").get("value").getAsString());
        assertEquals("Kaldırıldı", field(ban, "Status").get("value").getAsString());
        assertEquals(0x57F287, ban.get("color").getAsInt());
        var mute = embed(payload(settings(false), "remove", punishment(Kind.MUTE, PLAYER, "Notch", "REMOVED", 0), ""));
        assertTrue(mute.get("title").getAsString().endsWith("Susturma kaldırıldı"));
        var undo = embed(payload(settings(false), "undo", punishment(Kind.WARN, PLAYER, "Notch", "REVOKED", 0), ""));
        assertEquals(0x9B59B6, undo.get("color").getAsInt());
        assertTrue(undo.get("title").getAsString().endsWith("Ceza geri alındı"));
        assertEquals(4, undo.getAsJsonArray("fields").size()); // No duration or expiry for a warning.
    }

    @Test
    void customTextStaysWithinDiscordLimitsAndVisibleUuidsAreMasked() {
        var huge = new Webhook.Settings(true, settings(true).url(), Set.of("ban"), true,
                "T".repeat(5000), "F".repeat(5000));
        var embed = embed(payload(huge, "ban", punishment(Kind.BAN, PLAYER, PLAYER.toString(), "ACTIVE", 0), "x".repeat(5000)));
        assertTrue(embed.get("title").getAsString().length() <= 256);
        assertTrue(embed.getAsJsonObject("author").get("name").getAsString().contains("Hidden"));
        int total = embed.get("title").getAsString().length() + embed.getAsJsonObject("author").get("name").getAsString().length()
                + embed.getAsJsonObject("footer").get("text").getAsString().length();
        JsonArray fields = embed.getAsJsonArray("fields");
        assertTrue(fields.size() <= 25);
        for (var value : fields) {
            var field = value.getAsJsonObject();
            assertTrue(field.get("name").getAsString().length() <= 256);
            assertTrue(field.get("value").getAsString().length() <= 1024);
            assertFalse(field.get("value").getAsString().contains(PLAYER.toString()));
            total += field.get("name").getAsString().length() + field.get("value").getAsString().length();
        }
        assertTrue(total <= 6000, "Embed characters: " + total);
    }
}
