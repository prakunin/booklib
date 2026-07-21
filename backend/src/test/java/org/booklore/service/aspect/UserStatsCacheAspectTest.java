package org.booklore.service.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.UserStatsCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStatsCacheAspectTest {

    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private UserStatsCacheAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new UserStatsCacheAspect(authenticationService, new UserStatsCache());
    }

    @Test
    void servesSecondCallForSameUserFromCache() throws Throwable {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user(1L));
        stubJoinPoint("opA", new Object[]{2026});
        when(joinPoint.proceed()).thenReturn("R");

        assertThat(aspect.cache(joinPoint)).isEqualTo("R");
        assertThat(aspect.cache(joinPoint)).isEqualTo("R");

        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void doesNotShareCacheBetweenUsers() throws Throwable {
        stubJoinPoint("opA", new Object[]{2026});
        when(joinPoint.proceed()).thenReturn("R1", "R2");

        when(authenticationService.getAuthenticatedUser()).thenReturn(user(1L));
        assertThat(aspect.cache(joinPoint)).isEqualTo("R1");

        when(authenticationService.getAuthenticatedUser()).thenReturn(user(2L));
        assertThat(aspect.cache(joinPoint)).isEqualTo("R2");

        verify(joinPoint, times(2)).proceed();
    }

    @Test
    void recomputesAfterInvalidate() throws Throwable {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user(1L));
        stubJoinPoint("opA", new Object[]{2026});
        when(joinPoint.proceed()).thenReturn("R1", "R2");

        assertThat(aspect.cache(joinPoint)).isEqualTo("R1");
        aspect.invalidate();
        assertThat(aspect.cache(joinPoint)).isEqualTo("R2");

        verify(joinPoint, times(2)).proceed();
    }

    @Test
    void bypassesCacheWhenNoAuthenticatedUser() throws Throwable {
        when(authenticationService.getAuthenticatedUser()).thenReturn(null);
        when(joinPoint.proceed()).thenReturn("X");

        assertThat(aspect.cache(joinPoint)).isEqualTo("X");
        assertThat(aspect.cache(joinPoint)).isEqualTo("X");

        verify(joinPoint, times(2)).proceed();
    }

    @Test
    void usesStableStructuredCacheKeyForEquivalentDtoArguments() throws Throwable {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user(1L));
        when(joinPoint.proceed()).thenReturn("R");

        stubJoinPoint("opA", new Object[]{new StatsArg(2026, "ru")});
        assertThat(aspect.cache(joinPoint)).isEqualTo("R");

        stubJoinPoint("opA", new Object[]{new StatsArg(2026, "ru")});
        assertThat(aspect.cache(joinPoint)).isEqualTo("R");

        verify(joinPoint, times(1)).proceed();
    }

    private void stubJoinPoint(String methodName, Object[] args) throws NoSuchMethodException {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(sampleMethod(methodName));
        when(joinPoint.getArgs()).thenReturn(args);
    }

    private static Method sampleMethod(String name) throws NoSuchMethodException {
        return Sample.class.getDeclaredMethod(name);
    }

    private static BookLoreUser user(long id) {
        return BookLoreUser.builder().id(id).build();
    }

    @SuppressWarnings("unused")
    private static final class Sample {
        void opA() {
            // no-op: stub target method used only for its Method reference via reflection
        }
    }

    private record StatsArg(int year, String locale) {
    }
}
