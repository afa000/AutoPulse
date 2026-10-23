package dev.autopulse.database;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfiguration {
    @Bean FlywayMigrationStrategy preserveLegacyLocalHistory() {
        return MigrationSupport::migrate;
    }

    @Bean
    @ConditionalOnProperty(name = "autopulse.local-fixtures.enabled", havingValue = "true")
    ApplicationRunner seedLocalFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return args -> new LocalFixtures(jdbc, transactions).seed();
    }
}
