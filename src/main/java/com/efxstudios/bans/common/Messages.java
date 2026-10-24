package com.efxstudios.bans.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders language-file templates. Values are always inserted as unparsed placeholders, so player names
 * and reasons can never become MiniMessage tags, click events or other placeholders.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String UUID_PATTERN = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** Punishment IDs are only shown in record views and ban screens, never in announcements. */
    private static final Set<String> ID_VISIBLE = Set.of("screen.ban", "screen.ipban", "history-entry", "detail", "audit-entry", "inquiry.entry");

    private final Map<String, String> templates;
    private final Component prefix;
    private final ZoneId zone;
    private final DateTimeFormatter dates;
    private final Times.Units units;
    private final String hidden;
    private final String none;

    public Messages(String prefix, Map<String, String> templates, String zone) {
        this.templates = Map.copyOf(templates);
        this.prefix = MM.deserialize(prefix);
        this.zone = ZoneId.of(zone);
        this.dates = DateTimeFormatter.ofPattern(text("date-format", "yyyy-MM-dd HH:mm:ss")).withZone(this.zone);
        // Fail on load, not on first use, if a template is malformed.
        templates.values().forEach(MM::deserialize);
        Times.Units d = Times.Units.DEFAULT;
        this.units = new Times.Units(label("week", d.week()), label("day", d.day()), label("hour", d.hour()),
                label("minute", d.minute()), label("second", d.second()));
        this.hidden = text("hidden", "Hidden");
        this.none = text("none", "—");
    }

    /** time-units.hour (singular) and time-units.hours (plural). */
    private Times.Label label(String unit, Times.Label fallback) {
        return new Times.Label(text("time-units." + unit, fallback.one()), text("time-units." + unit + "s", fallback.many()));
    }

    public Component render(String key, Map<String, String> values) {
        return render(key, values, Map.of());
    }

    /** Trusted component placeholders, for interactive names; string values remain unparsed. */
    public Component render(String key, Map<String, String> values, Map<String, Component> components) {
        String template = templates.getOrDefault(key, "{prefix} <#FF5656>Missing message: " + key);
        template = template.replace("{uuid}", "");
        if (!key.startsWith("lists.") && !ID_VISIBLE.contains(key)) template = template.replaceAll("#?\\{id}", "");
        TagResolver.Builder tags = TagResolver.builder().resolver(Placeholder.component("efx_prefix", prefix));
        template = template.replace("{prefix}", "<efx_prefix>");
        for (var entry : values.entrySet()) {
            String tag = "efx_" + entry.getKey();
            template = template.replace("{" + entry.getKey() + "}", "<" + tag + ">");
            tags.resolver(Placeholder.unparsed(tag, hideUuid(Objects.requireNonNullElse(entry.getValue(), none), hidden)));
        }
        for (var entry : components.entrySet()) {
            String tag = "efx_" + entry.getKey();
            template = template.replace("{" + entry.getKey() + "}", "<" + tag + ">");
            tags.resolver(Placeholder.component(tag, entry.getValue()));
        }
        return MM.deserialize(template, tags.build());
    }

    /** Raw text of a key, for values that are inserted into other templates (usage lines, labels...). */
    public String text(String key, String fallback) {
        return templates.getOrDefault(key, fallback);
    }

    public String duration(long millis) {
        return Times.format(millis, units);
    }

    public static String hideUuid(String value) {
        return hideUuid(value, "Hidden");
    }

    public static String hideUuid(String value, String replacement) {
        return value.replaceAll(UUID_PATTERN, java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /** Standard placeholder values of a punishment. The IP is never part of them. */
    public Map<String, String> values(Punishment p) {
        long now = System.currentTimeMillis();
        String permanent = text("permanent", "Permanent");
        Map<String, String> v = new HashMap<>();
        v.put("player", p.name());
        v.put("staff", p.actorName());
        v.put("reason", p.reason());
        v.put("id", String.valueOf(p.id()));
        v.put("type", text("types." + p.kind().key(), p.kind().key()));
        v.put("duration", !p.kind().timed() ? none : p.expires() == 0 ? permanent : duration(p.expires() - p.created()));
        v.put("remaining", !p.kind().timed() ? none : p.expires() == 0 ? permanent : duration(Math.max(0, p.expires() - now)));
        v.put("created", date(p.created()));
        v.put("expires", p.expires() == 0 ? none : date(p.expires()));
        v.put("status", text("status." + p.status(now), p.status(now)));
        v.put("closed_at", p.closedAt() == 0 ? none : date(p.closedAt()));
        v.put("closed_by", p.closedName());
        v.put("close_reason", p.closeReason());
        v.put("ip", hidden);
        return v;
    }

    public String date(long millis) {
        return dates.format(Instant.ofEpochMilli(millis));
    }
}
