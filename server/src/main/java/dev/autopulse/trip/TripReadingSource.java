package dev.autopulse.trip;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Closed SQL identifiers. Select once per page, or for advisory coordinator discovery. */
enum TripReadingSource {
    RAW("telemetry_reading"), LEDGER("trip_ledger_reading"), HISTORY("telemetry_history_reading");
    private final String table;
    TripReadingSource(String table) { this.table=table; }
    String table() { return table; }
    static TripReadingSource forVehicle(JdbcTemplate jdbc, UUID vehicle) {
        // Upgrade rehearsals can execute the legacy worker before V923. Do not
        // query a ledger checkpoint/view that does not yet exist in that schema.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('trip_ledger_reading') is not null",Boolean.class))) return RAW;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from telemetry_replay_backfill where vehicle_id=? and complete)
                """,Boolean.class,vehicle)) ? LEDGER : RAW;
    }
}
