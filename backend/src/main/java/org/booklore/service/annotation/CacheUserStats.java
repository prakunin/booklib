package org.booklore.service.annotation;

import java.lang.annotation.*;

/**
 * Marks a per-user analytics method whose result should be served from {@code UserStatsCache}.
 * The cache key is derived from the authenticated user id, the method name, and its arguments.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheUserStats {
}
