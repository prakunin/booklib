package org.booklore.service.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

final class LegacyWordTextNormalizer {

    private static final char FIELD_START = 0x13;
    private static final char FIELD_SEPARATOR = 0x14;
    private static final char FIELD_END = 0x15;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r\\n|[\\r\\n]");

    private LegacyWordTextNormalizer() {
    }

    static List<String> normalize(String[] textPieces) {
        String[] filtered = filterFields(textPieces);
        List<String> paragraphs = new ArrayList<>(filtered.length);
        for (String textPiece : filtered) {
            if (textPiece == null) {
                paragraphs.add(null);
            } else {
                paragraphs.addAll(List.of(PARAGRAPH_BREAK.split(textPiece, -1)));
            }
        }
        return paragraphs;
    }

    private static String[] filterFields(String[] textPieces) {
        if (!hasBalancedFields(textPieces)) {
            return textPieces.clone();
        }

        String[] filtered = new String[textPieces.length];
        Deque<Boolean> fieldResults = new ArrayDeque<>();
        int instructionDepth = 0;
        for (int textPieceIndex = 0; textPieceIndex < textPieces.length; textPieceIndex++) {
            String textPiece = textPieces[textPieceIndex];
            if (textPiece == null) {
                filtered[textPieceIndex] = null;
                continue;
            }

            StringBuilder visible = new StringBuilder(textPiece.length());
            for (int charIndex = 0; charIndex < textPiece.length(); charIndex++) {
                char value = textPiece.charAt(charIndex);
                switch (value) {
                    case FIELD_START -> {
                        fieldResults.push(false);
                        instructionDepth++;
                    }
                    case FIELD_SEPARATOR -> {
                        if (!fieldResults.isEmpty() && !fieldResults.peek()) {
                            fieldResults.pop();
                            fieldResults.push(true);
                            instructionDepth--;
                        }
                    }
                    case FIELD_END -> {
                        if (!fieldResults.isEmpty() && !fieldResults.pop()) {
                            instructionDepth--;
                        }
                    }
                    default -> {
                        if (instructionDepth == 0) {
                            visible.append(value);
                        }
                    }
                }
            }
            filtered[textPieceIndex] = visible.toString();
        }
        return filtered;
    }

    private static boolean hasBalancedFields(String[] textPieces) {
        Deque<Boolean> fieldResults = new ArrayDeque<>();
        for (String textPiece : textPieces) {
            if (textPiece == null) {
                continue;
            }
            for (int index = 0; index < textPiece.length(); index++) {
                char value = textPiece.charAt(index);
                if (value == FIELD_START) {
                    fieldResults.push(false);
                } else if (value == FIELD_SEPARATOR) {
                    if (fieldResults.isEmpty() || fieldResults.peek()) {
                        return false;
                    }
                    fieldResults.pop();
                    fieldResults.push(true);
                } else if (value == FIELD_END) {
                    if (fieldResults.isEmpty()) {
                        return false;
                    }
                    fieldResults.pop();
                }
            }
        }
        return fieldResults.isEmpty();
    }
}
