package dev.autopulse.database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import org.flywaydb.core.Flyway;

/** Resolve the unchanged old local migration only if this database applied it. */
public final class MigrationSupport {
    private MigrationSupport() {}

    public static void migrate(Flyway flyway) {
        var config = flyway.getConfiguration();
        boolean legacyLocal = false;
        try (var connection = config.getDataSource().getConnection()) {
            String schema = config.getDefaultSchema();
            if (schema == null && config.getSchemas().length > 0) schema = config.getSchemas()[0];
            if (schema == null) schema = connection.getSchema();
            try (var exists = connection.prepareStatement("""
                    select exists (select 1 from information_schema.tables
                    where table_schema=? and table_name=?)
                    """)) {
                exists.setString(1, schema); exists.setString(2, config.getTable());
                try (var rows = exists.executeQuery()) {
                    rows.next();
                    if (rows.getBoolean(1)) {
                        String history = quote(schema) + "." + quote(config.getTable());
                        try (var query = connection.prepareStatement("select exists (select 1 from " + history
                                + " where version='900' and success)"); var applied = query.executeQuery()) {
                            applied.next(); legacyLocal = applied.getBoolean(1);
                        }
                    }
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot inspect migration history safely", error);
        }
        if (legacyLocal) {
            var locations = new ArrayList<>(Arrays.asList(config.getLocations()));
            var legacy = new org.flywaydb.core.api.Location("classpath:db/local");
            if (!locations.contains(legacy)) locations.add(legacy);
            Flyway.configure().configuration(config).locations(locations.toArray(org.flywaydb.core.api.Location[]::new))
                    .load().migrate();
        } else {
            flyway.migrate();
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
