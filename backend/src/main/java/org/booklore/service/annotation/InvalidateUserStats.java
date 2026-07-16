package org.booklore.service.annotation;

import java.lang.annotation.*;

/**
 * Marks a method that mutates a user's reading data; after it returns, that user's cached analytics
 * ({@code UserStatsCache}) are dropped so the next request recomputes them.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InvalidateUserStats {
}
