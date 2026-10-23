package dev.autopulse.database;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MaintenanceMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    HikariDataSource data;
    JdbcTemplate jdbc;
    String schema;
    @BeforeEach void isolate() {
        schema="maintenance_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=new HikariDataSource()) {
            admin.setJdbcUrl(POSTGRES.getJdbcUrl());admin.setUsername(POSTGRES.getUsername());admin.setPassword(POSTGRES.getPassword());admin.setMaximumPoolSize(1);
            new JdbcTemplate(admin).execute("create schema "+schema);
        }
        data=new HikariDataSource();data.setJdbcUrl(POSTGRES.getJdbcUrl()+"&currentSchema="+schema);data.setUsername(POSTGRES.getUsername());data.setPassword(POSTGRES.getPassword());data.setMaximumPoolSize(2);jdbc=new JdbcTemplate(data);
    }
    @AfterEach void close() {if(data!=null)data.close();}
    Flyway migration(String target) { var config=Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration");if(target!=null)config.target(target);return config.load(); }
    @Test void upgradeFrom932PreservesDiagnosticSnapshotsAndDoesNotInventHistoricalWork() {
        migration("932").migrate();UUID v=UUID.randomUUID(),d=UUID.randomUUID(),snapshot=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",v,"maintenance-upgrade-vehicle");jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",d,v,"maintenance-upgrade-device");
        jdbc.update("insert into diagnostic_snapshot(id,vehicle_id,device_id,boot_id,sequence_number,source,captured_at,received_at,payload) values(?,?,?,?,0,'SIMULATED',now(),now(),'{\"ecus\":[]}'::jsonb)",snapshot,v,d,UUID.randomUUID());
        var snapshots=jdbc.queryForList("select * from diagnostic_snapshot");var work=jdbc.queryForList("select * from diagnostic_work");
        var checksums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        MigrationSupport.migrate(migration(null));MigrationSupport.migrate(migration(null));
        assertThat(jdbc.queryForList("select * from diagnostic_snapshot")).isEqualTo(snapshots);assertThat(jdbc.queryForList("select * from diagnostic_work")).isEqualTo(work);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version::int<=932 order by installed_rank")).isEqualTo(checksums);
        assertEmpty();
    }
    @Test void freshSchemaHasIndependentHistoryAndIndexesWithoutRawDeletionReferences() {
        MigrationSupport.migrate(migration(null));assertEmpty();
        assertThat(migration(null).info().current().getVersion().toString()).isEqualTo("933");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname in ('maintenance_entry_chronology','maintenance_service_chronology','maintenance_recommendation_page','maintenance_work_due')",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype='f' and connamespace=current_schema()::regnamespace and conrelid::regclass::text like 'maintenance_%' and confrelid='telemetry_reading'::regclass",Integer.class)).isZero();
    }
    void assertEmpty() {for(String table:new String[]{"maintenance_vehicle","maintenance_profile","maintenance_entry","maintenance_entry_revision","maintenance_command","maintenance_work","maintenance_recommendation","maintenance_recommendation_revision","maintenance_thermal_scope","maintenance_rule_decision"}) assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();}
}
