package dev.winlandcraft;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Address/search interpretation, independent of the game and browser runtime. */
final class BrowserAddress {
    static String destination(String input) {
        String value = input.strip();
        if (value.isEmpty()) return null;
        if (value.equals("about:blank")) return value;
        if (!value.chars().anyMatch(Character::isWhitespace)) {
            try {
                String candidate = value.matches("(?i)^https?://.*") ? value : "https://" + value;
                URI uri = URI.create(candidate);
                String host = uri.getHost();
                if (host != null && (value.matches("(?i)^https?://.*") || host.contains(".") || host.equalsIgnoreCase("localhost") || host.contains(":")))
                    return candidate;
            } catch (IllegalArgumentException ignored) { }
        }
        return "https://www.google.com/search?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
