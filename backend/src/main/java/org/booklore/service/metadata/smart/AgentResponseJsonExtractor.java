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
        JsonBalance balance = new JsonBalance();
        for (int i = start; i < text.length(); i++) {
            if (balance.accept(text.charAt(i))) {
                return Optional.of(text.substring(start, i + 1));
            }
        }
        return Optional.empty();
    }

    private static final class JsonBalance {
        private int depth;
        private boolean inString;
        private boolean escaped;

        private boolean accept(char character) {
            if (escaped) {
                escaped = false;
            } else if (inString) {
                acceptStringCharacter(character);
            } else if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                return depth == 0;
            }
            return false;
        }

        private void acceptStringCharacter(char character) {
            if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                inString = false;
            }
        }
    }
}
