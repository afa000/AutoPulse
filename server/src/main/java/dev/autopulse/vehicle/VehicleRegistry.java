package dev.autopulse.vehicle;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleRegistry {
    public record DeviceRecord(UUID id, UUID vehicleId) {}
    private final JdbcTemplate jdbc;
    public VehicleRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> vehicle(String publicId) {
        return jdbc.query("select id from vehicle where public_id=?", (row, n) -> row.getObject(1, UUID.class), publicId).stream().findFirst();
    }
    public Optional<DeviceRecord> device(String publicId) {
        return jdbc.query("select id, vehicle_id from device where public_id=?", (row, n) ->
                new DeviceRecord(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), publicId).stream().findFirst();
    }
}
