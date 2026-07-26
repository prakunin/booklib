package org.booklore.model.dto.smart;

/**
 * Compares the rating the agent reported against the one the Goodreads parser fetched for the same
 * book id.
 * <p>
 * The verified value is the only one that may reach metadata. The reported one is kept beside it
 * because a systematic gap between the two is the cheapest available signal that the agent is
 * reading pages carelessly — a number that merely looks plausible cannot be spotted any other way.
 */
public record RatingVerification(
        Double reported,
        Double verified,
        boolean agrees
) {

    private static final double TOLERANCE = 0.01;

    public static RatingVerification of(Double reported, Double verified) {
        boolean agrees = reported != null && verified != null && Math.abs(reported - verified) <= TOLERANCE;
        return new RatingVerification(reported, verified, agrees);
    }
}
