package org.booklore.service.enrichment.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The key under which the Flibusta catalog files an author biography: the MD5 of the lowercased
 * author name, UTF-8, with whitespace collapsed — {@code "Дэниел Хэндлер"} is filed as
 * {@code md5("хэндлер дэниел")}, surname first.
 * <p>
 * This class used to assert that the stored name is always already in that order, because
 * {@code InpxParser} turns the raw {@code "Хэндлер,Дэниел,"} field into {@code "Хэндлер Дэниел"}.
 * Measured against the 702,511-book Flibusta library on 2026-08-04, that is true of most names and
 * false of a large minority:
 *
 * <pre>
 * catalog AUTHOR_BIO keys                                            56,546
 * author rows                                                       271,250
 *
 * stored name hashed as-is                        47,412 authors → 47,406 keys
 * + last token moved to the front                +21,689 authors →    +716 keys
 * </pre>
 *
 * The measured cause is <b>ordering, not the patronymic</b>. A patronymic is no barrier at all —
 * 21,704 three-token names already match as stored. What fails is the 21,689 authors whose stored
 * name is given-name first, with or without a patronymic: 13,185 two-token ones such as
 * {@code "Кевин Митник"} (filed as {@code "митник кевин"}) and 8,433 three-token ones such as
 * {@code "Анатолий Владимирович Афанасьев"} (filed as {@code "афанасьев анатолий владимирович"}).
 * Both are repaired by the same operation: move the last whitespace-separated token to the front.
 * <p>
 * Candidates that were measured and rejected, because they collapse different people onto one key
 * rather than reordering one person's name:
 *
 * <pre>
 * first two tokens only        +2,045 authors, but 13 different "Кузнецов Александр ...ович"
 *                                     rows land on md5("кузнецов александр")
 * surname alone                +9,148 authors onto 261 keys — 36 authors per biography
 * surname + initials             +520 authors onto 282 keys
 * ё folded to е                     +48 authors
 * hyphen folded to a space           +8 authors
 * </pre>
 *
 * After the two accepted candidates, 8,424 of the 56,546 catalog keys are still unreached. That
 * residue is a coverage gap, not a key-derivation gap: of 2,273 residue keys whose canonical name
 * could be recovered from the biography text, only 34 exist in the {@code author} table at all. They
 * are translators, editors and academics this library holds no books by.
 * <p>
 * MD5 here is a lookup key in a third-party data file, not a security primitive; there is nothing to
 * choose, the catalog picked it.
 */
public final class FlibustaAuthorKey {

    private FlibustaAuthorKey() {
    }

    /**
     * The key for the name exactly as this application stores it — the first and most specific
     * candidate. Callers that need the fallbacks want {@link #candidates(String)}.
     */
    public static String of(String authorName) {
        String normalized = normalize(authorName);
        return normalized == null ? null : hash(normalized);
    }

    /**
     * Every key worth trying for one stored name, most specific first, deduplicated:
     *
     * <ol>
     *   <li>the stored name as-is — the catalog's own order, and the only candidate that adds no
     *       assumption about which token is the surname;</li>
     *   <li>the stored name with its last token moved to the front — the repair for a name stored
     *       given-name first.</li>
     * </ol>
     *
     * The order is fixed here rather than left to a caller or to a collection's iteration order,
     * because the caller stops at the first candidate that resolves. 126 authors reach a
     * <em>different</em> catalog key under each candidate — 110 of those two keys holding genuinely
     * different text — and this order is what settles them: the stored-order biography is written and
     * the rotation is never read. That is a precedence and not a guess. The stored name assumes nothing
     * about which token is the surname; the rotation asserts one, so it is the weaker evidence of the
     * two. Both of the 110 that were read in full ({@code Рюноскэ Акутагава}, {@code Ба Цзинь}) turned
     * out to be one person the catalog files twice under both orders, which is what that precedence
     * predicts.
     * <p>
     * The list stops at two: every further normalisation measured either drops a token, and with it the
     * distinction between different people who share a surname and a given name, or reached fewer than
     * 50 authors.
     * <p>
     * A rotation is always offered, even for a name already in the catalog's order — nothing in the
     * stored string says which order it is in. For those names the rotation is simply a key the
     * catalog does not hold ({@code "Афанасьев Анатолий Владимирович"} rotates to the meaningless
     * {@code "владимирович афанасьев анатолий"}), so it costs one index lookup and matches nothing.
     * It is not a guess: an MD5 either is the catalog's key or it is not.
     */
    public static List<String> candidates(String authorName) {
        String normalized = normalize(authorName);
        if (normalized == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(2);
        keys.add(hash(normalized));
        String surnameFirst = moveLastTokenToFront(normalized);
        if (surnameFirst != null && !surnameFirst.equals(normalized)) {
            keys.add(hash(surnameFirst));
        }
        return List.copyOf(keys);
    }

    private static String normalize(String authorName) {
        if (authorName == null || authorName.isBlank()) {
            return null;
        }
        return authorName.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * {@code "анатолий владимирович афанасьев"} to {@code "афанасьев анатолий владимирович"} — only
     * the trailing token moves, so a patronymic keeps its place behind the given name. Null for a
     * single-token name, which has no rotation.
     */
    private static String moveLastTokenToFront(String normalized) {
        int lastSpace = normalized.lastIndexOf(' ');
        if (lastSpace < 0) {
            return null;
        }
        return normalized.substring(lastSpace + 1) + " " + normalized.substring(0, lastSpace);
    }

    private static String hash(String normalized) {
        return HexFormat.of().formatHex(md5(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("java:S4790") // not a security hash: it is the lookup key the catalog files bios under
    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required to read the local catalog", e);
        }
    }
}
