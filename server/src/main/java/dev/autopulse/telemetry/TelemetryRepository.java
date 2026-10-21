package dev.autopulse.telemetry;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TelemetryRepository {
    public record Latest(JsonNode reading, String receivedAt) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public TelemetryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public String store(UUID vehicleId, UUID deviceId, ValidatedReading sample) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("vehicle", vehicleId).addValue("device", deviceId)
                .addValue("boot", sample.bootId()).addValue("sequence", new BigDecimal(sample.sequenceNumber()))
                .addValue("recorded", Timestamp.from(sample.recordedAt())).addValue("source", sample.payload().get("source").stringValue())
                .addValue("payload", sample.payload().toString());
        for (String field : TelemetryValidator.NUMBERS) {
            JsonNode value = sample.payload().get(field);
            parameters.addValue(field, value.isNull() ? null : value.decimalValue().doubleValue(), Types.DOUBLE);
        }
        JsonNode mil = sample.payload().get("milOn");
        parameters.addValue("milOn", mil.isNull() ? null : mil.booleanValue(), Types.BOOLEAN);
        int inserted = jdbc.update("""
                insert into telemetry_reading
                (id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,
                 rpm,speed_kph,coolant_temp_c,engine_load_pct,throttle_position_pct,intake_temp_c,
                 maf_gps,fuel_level_pct,control_module_voltage_v,mil_on,payload)
                values (:id,:vehicle,:device,:boot,:sequence,:recorded,:source,
                 :rpm,:speedKph,:coolantTempC,:engineLoadPct,:throttlePositionPct,:intakeTempC,
                 :mafGps,:fuelLevelPct,:controlModuleVoltageV,:milOn,cast(:payload as jsonb))
                on conflict (device_id,boot_id,sequence_number) do nothing
                """, parameters);
        if (inserted == 1) return "accepted";
        Boolean exact = jdbc.queryForObject("""
                select payload=cast(:payload as jsonb) from telemetry_reading
                where device_id=:device and boot_id=:boot and sequence_number=:sequence
                """, parameters, Boolean.class);
        return Boolean.TRUE.equals(exact) ? "duplicate" : "conflict";
    }

    public Optional<Latest> latest(UUID vehicleId) {
        return jdbc.query("""
                select payload::text,received_at from telemetry_reading where vehicle_id=:vehicle
                order by recorded_at desc,device_id desc,boot_id desc,sequence_number desc limit 1
                """, new MapSqlParameterSource("vehicle", vehicleId), (row, n) ->
                new Latest(mapper.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readTree(row.getString(1)), row.getTimestamp(2).toInstant().toString())).stream().findFirst();
    }
}
