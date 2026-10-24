package com.efxstudios.bans.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class Addresses {
    private Addresses() {}

    /**
     * Normalizes a literal IPv4 or IPv6 address, or returns null.
     * Hostnames are never resolved: user input must not trigger DNS lookups.
     */
    public static String parse(String input) {
        if (input.contains(":")) {
            if (!input.matches("[0-9a-fA-F:.]+")) return null;
            // With ':' present, the JDK parses a literal or throws; it does not fall back to DNS.
            try {
                return InetAddress.getByName(input).getHostAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        String[] parts = input.split("\\.", -1);
        if (parts.length != 4) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}")) return null;
            int octet = Integer.parseInt(part);
            if (octet > 255) return null;
            if (!out.isEmpty()) out.append('.');
            out.append(octet);
        }
        return out.toString();
    }
}
