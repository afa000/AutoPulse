package dev.autopulse.database;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local/test registration, independent of Flyway version ordering. */
public final class LocalFixtures {
    public static final UUID VEHICLE = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final UUID EMPTY = UUID.fromString("10000000-0000-4000-8000-000000000002");
    public static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public LocalFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
    }

    public void seed() {
        transaction.executeWithoutResult(status -> {
            vehicle(VEHICLE, "venue-2021"); vehicle(EMPTY, "empty-vehicle");
            jdbc.update("insert into device (id,vehicle_id,public_id) values (?,?,?) on conflict do nothing",
                    DEVICE, VEHICLE, "sim-device-001");
            require(jdbc.queryForObject("select count(*) from device where id=? and vehicle_id=? and public_id=?",
                    Integer.class, DEVICE, VEHICLE, "sim-device-001") == 1);
            jdbc.update("insert into trip_policy (vehicle_id,primary_device_id) values (?,?) on conflict do nothing", VEHICLE, DEVICE);
            require(jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?", UUID.class, VEHICLE).equals(DEVICE));
        });
    }

    private void vehicle(UUID id, String publicId) {
        jdbc.update("insert into vehicle (id,public_id) values (?,?) on conflict do nothing", id, publicId);
        require(jdbc.queryForObject("select count(*) from vehicle where id=? and public_id=?", Integer.class, id, publicId) == 1);
    }
    private static void require(boolean matches) {
        if (!matches) throw new IllegalStateException("Local fixture identity/policy conflict; existing data was not overwritten");
    }
}
