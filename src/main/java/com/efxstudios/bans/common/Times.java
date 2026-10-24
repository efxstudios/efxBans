package com.efxstudios.bans.common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Times {

    public record Label(String one, String many) {
        String of(long value) {
            return value == 1 ? one : many;
        }
    }

    public record Units(Label week, Label day, Label hour, Label minute, Label second) {
        public static final Units DEFAULT = new Units(new Label("week", "weeks"), new Label("day", "days"), new Label("hour", "hours"),
                new Label("minute", "minutes"), new Label("second", "seconds"));
    }

    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);

    private Times() {}

    public static boolean looksLikeDuration(String text) {
        return !text.isEmpty() && Character.isDigit(text.charAt(0));
    }

    public static long parse(String text) {
        Matcher matcher = PART.matcher(text);
        int end = 0;
        long total = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) throw new Problem("invalid-duration");
                long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "s" -> 1000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    default -> 604_800_000L;
                };
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier));
                end = matcher.end();
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new Problem("invalid-duration");
        }
        if (end != text.length() || end == 0 || total <= 0) throw new Problem("invalid-duration");
        return total;
    }

    public static String format(long millis) {
        return format(millis, Units.DEFAULT);
    }

    public static String format(long millis, Units labels) {
        if (millis <= 0) return "0 " + labels.second().many();
        long seconds = millis / 1000 + (millis % 1000 == 0 ? 0 : 1);
        long[] units = {604800, 86400, 3600, 60, 1};
        Label[] names = {labels.week(), labels.day(), labels.hour(), labels.minute(), labels.second()};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < units.length; i++) {
            long value = seconds / units[i];
            seconds %= units[i];
            if (value == 0) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(value).append(' ').append(names[i].of(value));
        }
        return result.toString();
    }
}
