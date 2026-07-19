package org.booklore.config;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(
            @Value("${booklore.flyway.repair-on-validate-error:false}") boolean repairOnValidateError) {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (FlywayValidateException e) {
                if (!repairOnValidateError) {
                    throw e;
                }
                flyway.repair();
                flyway.migrate();
            }
        };
    }
}
