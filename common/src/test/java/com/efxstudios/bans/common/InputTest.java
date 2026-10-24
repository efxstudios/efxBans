package com.efxstudios.bans.common;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Parsing and rendering of untrusted input: durations, addresses, message values. */
class InputTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void durationRequiresEntireValidInputAndRejectsOverflow() {
        assertEquals(93_600_000, Times.parse("1d2h"));
        assertEquals(1_800_000, Times.parse("30m"));
        for (String value : new String[]{"0s", "-1d", "2years", "2h trailing", "99999999999999999999999999d", "1m2", ""})
            assertThrows(IllegalArgumentException.class, () -> Times.parse(value), value);
        assertEquals("2 saat", Times.format(7_200_000));
        assertEquals("1 day 2 hours", Times.format(93_600_000, new Times.Units("weeks", "day", "hours", "min", "s")));
    }

    @Test
    void addressParsingNeverAcceptsHostnamesOrSql() {
        assertEquals("127.0.0.1", Addresses.parse("127.000.0.1"));
        assertNotNull(Addresses.parse("2001:db8::1"));
        for (String text : new String[]{"localhost", "example.com", "127.0.0.999", "1.2.3.4'", "1.2.3.4:25565", "fe80::1%eth0", "dead:beef:zz"})
            assertNull(Addresses.parse(text), text);
    }

    @Test
    void userDataIsLiteralEvenWhenItContainsTagsPrefixOrPlaceholders() {
        Messages m = new Messages("<red>efxBans</red>", Map.of("sample", "{prefix} <blue>{reason}</blue> {player}"), "Europe/Istanbul");
        String attack = "<click:run_command:'/op bad'><red>Hile</red></click> {prefix} {player}";

        var rendered = m.render("sample", Map.of("reason", attack, "player", "<green>NAME</green>"));

        assertEquals("efxBans " + attack + " <green>NAME</green>", PLAIN.serialize(rendered));
        assertNull(rendered.clickEvent());
    }

    @Test
    void identifiersAreLimitedToRecordViewsAndUuidsAreRedacted() {
        String template = "#{id} {uuid} {player}";
        Messages m = new Messages("", Map.of("muted", template, "inquiry.entry", template, "screen.ban", template), "UTC");
        String uuid = "12345678-1234-1234-1234-123456789abc";
        var values = Map.of("id", "42", "uuid", uuid, "player", uuid);

        assertEquals("  Gizli", PLAIN.serialize(m.render("muted", values)));
        assertEquals("#42  Gizli", PLAIN.serialize(m.render("inquiry.entry", values)));
        assertEquals("#42  Gizli", PLAIN.serialize(m.render("screen.ban", values)));
        assertFalse(Webhook.quote(uuid).contains(uuid));
    }

    @Test
    void configurableLabelsAreUsedForDurationsAndHiddenValues() {
        Messages m = new Messages("", Map.of("sample", "{player}", "hidden", "Hidden", "time-units.hour", "hours"), "UTC");
        assertEquals("Hidden", PLAIN.serialize(m.render("sample", Map.of("player", "12345678-1234-1234-1234-123456789abc"))));
        assertEquals("2 hours", m.duration(7_200_000));
    }

    @Test
    void jsonEscapesControlCharactersAndQuotes() {
        assertEquals("\"a\\\"\\\\\\n\\u0001\"", Webhook.quote("a\"\\\n\u0001"));
    }
}
