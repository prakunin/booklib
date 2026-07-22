package org.booklore.service.aspect;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.UserStatsCache;
import org.booklore.service.annotation.InvalidateUserStats;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link UserStatsCacheAspect} through the real Spring AOP proxy chain (not by calling the
 * advice methods directly). This is what surfaces the interceptor-ordering bug: when the aspect is
 * ordered at {@code HIGHEST_PRECEDENCE}, its {@code @AfterReturning} advice sorts ahead of
 * {@code ExposeInvocationInterceptor} and fails with "No MethodInvocation found" when it resolves
 * the join point. Ordering it at {@code HIGHEST_PRECEDENCE + 1} keeps it outside the transaction
 * advice while letting the invocation-exposing interceptor run first.
 */
class UserStatsCacheAspectProxyTest {

    private static final long USER_ID = 7L;

    @Test
    void invalidatingMethodReturnsCleanlyThroughProxyAndDropsCache() {
        try (var ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            UserStatsCache cache = ctx.getBean(UserStatsCache.class);
            StatsMutator mutator = ctx.getBean(StatsMutator.class);

            AtomicInteger loads = new AtomicInteger();
            assertThat(cache.get(USER_ID, "op", loads::incrementAndGet)).isEqualTo(1);
            // Cached: a second read must not recompute.
            assertThat(cache.get(USER_ID, "op", loads::incrementAndGet)).isEqualTo(1);

            // Before the ordering fix this throws IllegalStateException: No MethodInvocation found.
            assertThatCode(mutator::mutate).doesNotThrowAnyException();

            // @AfterReturning invalidate() ran: the cache is dropped and the loader runs again.
            assertThat(cache.get(USER_ID, "op", loads::incrementAndGet)).isEqualTo(2);
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        AuthenticationService authenticationService() {
            AuthenticationService auth = mock(AuthenticationService.class);
            when(auth.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(USER_ID).build());
            return auth;
        }

        @Bean
        UserStatsCache userStatsCache() {
            return new UserStatsCache();
        }

        @Bean
        UserStatsCacheAspect userStatsCacheAspect(AuthenticationService auth, UserStatsCache cache) {
            return new UserStatsCacheAspect(auth, cache);
        }

        @Bean
        StatsMutator statsMutator() {
            return new StatsMutator();
        }
    }

    static class StatsMutator {
        @InvalidateUserStats
        public void mutate() {
            // no-op: the aspect's @AfterReturning advice is the behaviour under test
        }
    }
}
