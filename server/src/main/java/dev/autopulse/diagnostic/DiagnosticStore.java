package dev.autopulse.diagnostic;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.vehicle.VehicleRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DiagnosticStore {
    public record Receipt(UUID snapshotId,String result,Instant receivedAt) {}
    private final JdbcTemplate jdbc;
    private final VehicleRegistry registry;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public DiagnosticStore(JdbcTemplate jdbc,VehicleRegistry registry,PlatformTransactionManager transactions) { this(jdbc,registry,transactions,Clock.systemUTC()); }
    public DiagnosticStore(JdbcTemplate jdbc,VehicleRegistry registry,PlatformTransactionManager transactions,Clock clock) {
        this.jdbc=jdbc;this.registry=registry;this.clock=clock;tx=new TransactionTemplate(transactions);tx.setTimeout(10);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Receipt accept(DiagnosticValidator.Snapshot s) {
        return tx.execute(t->{
            UUID vehicle=registry.vehicle(s.vehicle()).orElseThrow(()->failure(404,"UNKNOWN_VEHICLE"));
            var device=registry.device(s.device()).orElseThrow(()->failure(404,"UNKNOWN_DEVICE"));
            if(!device.vehicleId().equals(vehicle)) throw failure(409,"DEVICE_VEHICLE_MISMATCH");
            var existing=jdbc.queryForList("select id,received_at,payload=cast(? as jsonb) as exact from diagnostic_snapshot where device_id=? and boot_id=? and sequence_number=?",s.payload().toString(),device.id(),s.boot(),s.sequence());
            if(!existing.isEmpty()) return duplicate(existing.getFirst());
            Instant received=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(s.captured().isAfter(received.plusSeconds(120))) throw DiagnosticValidator.invalid();
            UUID id=UUID.randomUUID();
            int inserted=jdbc.update("""
                insert into diagnostic_snapshot(id,vehicle_id,device_id,boot_id,sequence_number,source,captured_at,received_at,payload)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb)) on conflict(device_id,boot_id,sequence_number) do nothing
                """,id,vehicle,device.id(),s.boot(),s.sequence(),s.source(),Timestamp.from(s.captured()),Timestamp.from(received),s.payload().toString());
            if(inserted==1) return new Receipt(id,"ACCEPTED",received);
            return duplicate(jdbc.queryForMap("select id,received_at,payload=cast(? as jsonb) as exact from diagnostic_snapshot where device_id=? and boot_id=? and sequence_number=?",s.payload().toString(),device.id(),s.boot(),s.sequence()));
        });
    }
    private static Receipt duplicate(Map<String,Object> row) {
        if(!Boolean.TRUE.equals(row.get("exact"))) throw failure(409,"IDENTITY_CONFLICT");
        return new Receipt((UUID)row.get("id"),"DUPLICATE",((Timestamp)row.get("received_at")).toInstant());
    }
    static ApiFailure failure(int status,String code) { return new ApiFailure(status,code,"Diagnostic request could not be applied",List.of()); }
}
