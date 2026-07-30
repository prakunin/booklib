package org.booklore.service.metadata.smart;

import java.util.Optional;

/**
 * Pulls the JSON object out of an agent response.
 * <p>
 * An agent asked for "JSON only" still sometimes wraps it in a markdown fence or prefixes a
 * sentence, and stderr chatter is merged into the same stream. Rather than trusting the whole
 * output to parse, this scans for the first balanced top-level object.
 */
public final class AgentResponseJsonExtractor {

    private AgentResponseJsonExtractor() {
    }

    public static Optional<String> extractObject(String response) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        int start = response.indexOf('{');
        while (start >= 0) {
            Optional<String> candidate = readBalancedObject(response, start);
            if (candidate.isPresent()) {
                return candidate;
            }
            start = response.indexOf('{', start + 1);
        }
        return Optional.empty();
    }

    /**
     * Brace counting has to respect string literals: a description quoting a brace, or an escaped
     * quote inside one, would otherwise end the object early or never.
     */
    private static Optional<String> readBalancedObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return Optional.of(text.substring(start, i + 1));
                    }
                }
                default -> {
                    // Nothing outside strings and braces affects balance.
                }
            }
        }
        return Optional.empty();
    }
}
