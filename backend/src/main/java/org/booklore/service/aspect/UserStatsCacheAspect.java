package org.booklore.service.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.UserStatsCache;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Serves {@code @CacheUserStats} methods from {@link UserStatsCache} and drops a user's cached
 * analytics after an {@code @InvalidateUserStats} method returns.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so the cache wraps <em>outside</em> Spring's
 * transaction advice: a cache hit skips the transaction entirely, and invalidation runs only after
 * the recording transaction has committed.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class UserStatsCacheAspect {

    private static final ObjectMapper CACHE_KEY_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AuthenticationService authenticationService;
    private final UserStatsCache userStatsCache;

    @Around("@annotation(org.booklore.service.annotation.CacheUserStats)")
    public Object cache(ProceedingJoinPoint joinPoint) throws Throwable {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            return joinPoint.proceed();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String op = signature.getMethod().toGenericString() + stableArgsKey(joinPoint.getArgs());
        return userStatsCache.get(user.getId(), op, () -> proceedUnchecked(joinPoint));
    }

    @AfterReturning("@annotation(org.booklore.service.annotation.InvalidateUserStats)")
    public void invalidate() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user != null && user.getId() != null) {
            userStatsCache.invalidateUser(user.getId());
        }
    }

    private static Object proceedUnchecked(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stableArgsKey(Object[] args) {
        try {
            return CACHE_KEY_MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            return Arrays.deepToString(args);
        }
    }
}
