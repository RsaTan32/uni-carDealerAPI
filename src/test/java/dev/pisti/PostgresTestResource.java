package dev.pisti;

import java.util.Map;

import org.testcontainers.postgresql.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer database;

    @Override
    public Map<String, String> start() {
        database = new PostgreSQLContainer("postgres:17");
        database.withDatabaseName("carrental_auth_tests")
                .withUsername("test")
                .withPassword("test");
        database.start();
        return Map.of(
                "quarkus.datasource.jdbc.url", database.getJdbcUrl(),
                "quarkus.datasource.username", database.getUsername(),
                "quarkus.datasource.password", database.getPassword(),
                "quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
                "quarkus.flyway.migrate-at-start", "false",
                "app.auth.bootstrap-admin.email", "bootstrap@example.com",
                "app.auth.bootstrap-admin.password", "bootstrap-test-password");
    }

    @Override
    public void stop() {
        if (database != null) {
            database.close();
        }
    }
}