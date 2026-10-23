package dev.autopulse.database;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DiagnosticMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    HikariDataSource data;
    JdbcTemplate jdbc;
    String schema;
    @BeforeEach void isolated() {
        schema="diagnostic_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=new HikariDataSource()) {
            admin.setJdbcUrl(POSTGRES.getJdbcUrl());admin.setUsername(POSTGRES.getUsername());admin.setPassword(POSTGRES.getPassword());admin.setMaximumPoolSize(1);
            new JdbcTemplate(admin).execute("CREATE SCHEMA "+schema);
        }
        data=new HikariDataSource();data.setJdbcUrl(POSTGRES.getJdbcUrl()+"&currentSchema="+schema);data.setUsername(POSTGRES.getUsername());data.setPassword(POSTGRES.getPassword());data.setMaximumPoolSize(2);
        jdbc=new JdbcTemplate(data);
    }
    @AfterEach void close() { if(data!=null) data.close(); }
    Flyway current() { return Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").load(); }
    @Test void upgradeFrom931AndRepeatedRestartPreserveOriginalTelemetryAndAppliedChecksums() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("931").load().migrate();
        UUID vehicle=UUID.randomUUID(),device=UUID.randomUUID(),raw=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,"diag-migration-vehicle");
        jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",device,vehicle,"diag-migration-device");
        var mapper=JsonMapper.builder().build();var payload=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId","diag-migration-vehicle").put("deviceId","diag-migration-device");
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload) values(?,?,?,?,?,?,?,cast(? as jsonb))",
                raw,vehicle,device,UUID.fromString(payload.get("bootId").asText()),payload.get("sequenceNumber").bigIntegerValue(),Timestamp.from(Instant.parse(payload.get("recordedAt").asText())),"SIMULATED",payload.toString());
        var before=jdbc.queryForList("select * from telemetry_reading");
        var checksums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        MigrationSupport.migrate(current());MigrationSupport.migrate(current());
        assertThat(current().info().current().getVersion().toString()).isEqualTo("933");
        assertThat(jdbc.queryForList("select * from telemetry_reading")).isEqualTo(before);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version::int<=931 order by installed_rank")).isEqualTo(checksums);
        assertEmptyDiagnosticTables();
    }
    @Test void freshMigrationCreatesBoundedIndexedIndependentEmptyEvidenceTables() {
        MigrationSupport.migrate(current());assertEmptyDiagnosticTables();
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename like 'diagnostic_%'",String.class))
                .containsExactlyInAnyOrder("diagnostic_vehicle","diagnostic_snapshot","diagnostic_work","diagnostic_scope","diagnostic_event","diagnostic_application","diagnostic_transition");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname like 'diagnostic_event%page'",Integer.class)).isEqualTo(4);
    }
    private void assertEmptyDiagnosticTables() {
        for(String table:new String[]{"diagnostic_vehicle","diagnostic_snapshot","diagnostic_work","diagnostic_scope","diagnostic_event","diagnostic_application","diagnostic_transition"})
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();
    }
}
