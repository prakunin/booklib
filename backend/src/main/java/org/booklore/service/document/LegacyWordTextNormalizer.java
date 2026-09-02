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
        FieldNesting nesting = new FieldNesting();
        for (int textPieceIndex = 0; textPieceIndex < textPieces.length; textPieceIndex++) {
            String textPiece = textPieces[textPieceIndex];
            filtered[textPieceIndex] = textPiece == null ? null : nesting.visibleText(textPiece);
        }
        return filtered;
    }

    private static boolean hasBalancedFields(String[] textPieces) {
        FieldNesting nesting = new FieldNesting();
        for (String textPiece : textPieces) {
            if (textPiece != null && !nesting.consumesBalanced(textPiece)) {
                return false;
            }
        }
        return nesting.isClosed();
    }

    /**
     * Tracks Word field codes across text pieces. A field is {@code FIELD_START instruction
     * FIELD_SEPARATOR result FIELD_END}; only the result part is visible text, and fields nest.
     */
    private static final class FieldNesting {

        private enum Part { INSTRUCTION, RESULT }

        private final Deque<Part> openFields = new ArrayDeque<>();
        private int instructionDepth;

        /** The text piece with every field instruction removed, leaving field results and plain text. */
        String visibleText(String textPiece) {
            StringBuilder visible = new StringBuilder(textPiece.length());
            for (int charIndex = 0; charIndex < textPiece.length(); charIndex++) {
                char value = textPiece.charAt(charIndex);
                switch (value) {
                    case FIELD_START -> openField();
                    case FIELD_SEPARATOR -> enterResult();
                    case FIELD_END -> closeField();
                    default -> {
                        if (instructionDepth == 0) {
                            visible.append(value);
                        }
                    }
                }
            }
            return visible.toString();
        }

        /** Consumes the piece; {@code false} as soon as a separator or end has no field to belong to. */
        boolean consumesBalanced(String textPiece) {
            for (int index = 0; index < textPiece.length(); index++) {
                char value = textPiece.charAt(index);
                if (value == FIELD_START) {
                    openField();
                } else if (value == FIELD_SEPARATOR && !enterResult()) {
                    return false;
                } else if (value == FIELD_END && !closeField()) {
                    return false;
                }
            }
            return true;
        }

        boolean isClosed() {
            return openFields.isEmpty();
        }

        private void openField() {
            openFields.push(Part.INSTRUCTION);
            instructionDepth++;
        }

        /** @return {@code false} when there is no instruction part to leave */
        private boolean enterResult() {
            if (openFields.peek() != Part.INSTRUCTION) {
                return false;
            }
            openFields.pop();
            openFields.push(Part.RESULT);
            instructionDepth--;
            return true;
        }

        /** @return {@code false} when there is no open field to close */
        private boolean closeField() {
            Part closed = openFields.poll();
            if (closed == null) {
                return false;
            }
            if (closed == Part.INSTRUCTION) {
                instructionDepth--;
            }
            return true;
        }
    }
}
