package dev.autopulse.maintenance;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="autopulse.maintenance.processing-enabled",havingValue="true")
public class MaintenanceSchedule {
    private final MaintenanceProjector projector;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean ready;
    public MaintenanceSchedule(MaintenanceProjector projector) { this.projector=projector; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready=true; }
    @EventListener(ContextClosedEvent.class) public void close() { ready=false; }
    @Scheduled(fixedDelay=1000,initialDelay=1000) public void tick() {
        if(!ready||!running.compareAndSet(false,true))return;
        try { projector.poll(Instant.now()); }
        catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(MaintenanceSchedule.class).warn("Maintenance poll unavailable; failureType={}",failure.getClass().getSimpleName()); }
        finally { running.set(false); }
    }
}
