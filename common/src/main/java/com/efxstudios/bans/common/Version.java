package com.efxstudios.bans.common;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Semantic Versioning 2.0.0 version (major.minor.patch[-pre.release][+build]).
 * Build metadata is accepted but ignored, exactly as the specification requires for precedence.
 */
public record Version(int major, int minor, int patch, List<String> preRelease) implements Comparable<Version> {
    private static final String IDENTIFIER = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-(" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*))?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    public Version {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("Negative version number");
        preRelease = List.copyOf(preRelease);
    }

    /** Parses "1.2.3", "v1.2.3" or "1.2.3-beta.1+build.5". Throws IllegalArgumentException otherwise. */
    public static Version parse(String text) {
        String value = text.strip();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        Matcher matcher = SEMVER.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Not a semantic version: " + text);
        try {
            List<String> pre = matcher.group(4) == null ? List.of() : List.of(matcher.group(4).split("\\."));
            return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), pre);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Version number too large: " + text, e);
        }
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        // A release ranks above any pre-release of the same major.minor.patch.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return Boolean.compare(preRelease.isEmpty(), other.preRelease.isEmpty());
        for (int i = 0; i < Math.min(preRelease.size(), other.preRelease.size()); i++) {
            result = compareIdentifier(preRelease.get(i), other.preRelease.get(i));
            if (result != 0) return result;
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            int length = Integer.compare(left.length(), right.length());
            return length != 0 ? length : left.compareTo(right);
        }
        // Numeric identifiers always have lower precedence than alphanumeric ones.
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        return left.compareTo(right);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? core : core + "-" + String.join(".", preRelease);
    }
}
