package org.booklore.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FlywayConfigTest {

    @Test
    void flywayMigrationStrategy_DoesNotRepairValidationFailuresByDefault() {
        Flyway flyway = mock(Flyway.class);
        FlywayValidateException validateException = validateException();
        when(flyway.migrate()).thenThrow(validateException);

        var strategy = new FlywayConfig().flywayMigrationStrategy(false);

        assertThatThrownBy(() -> strategy.migrate(flyway)).isSameAs(validateException);
        verify(flyway, never()).repair();
    }

    @Test
    void flywayMigrationStrategy_RepairsOnlyWhenExplicitlyEnabled() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenThrow(validateException()).thenReturn(null);

        var strategy = new FlywayConfig().flywayMigrationStrategy(true);

        strategy.migrate(flyway);

        verify(flyway).repair();
        verify(flyway, times(2)).migrate();
    }

    private FlywayValidateException validateException() {
        return new FlywayValidateException(new ErrorDetails(mock(ErrorCode.class), "validation failed"), "validation failed");
    }
}
