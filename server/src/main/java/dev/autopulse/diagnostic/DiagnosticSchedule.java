package dev.autopulse.diagnostic;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.*;
import org.springframework.scheduling.annotation.*;

/** Disabled by default; one bounded category page per tick, never sourced from SSE. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="autopulse.diagnostics.processing-enabled",havingValue="true")
public class DiagnosticSchedule {
    private final DiagnosticProjector projector;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean ready;
    public DiagnosticSchedule(DiagnosticProjector projector) { this.projector=projector; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready=true; }
    @EventListener(ContextClosedEvent.class) public void close() { ready=false; }
    @Scheduled(fixedDelay=1000,initialDelay=1000) public void tick() {
        if(!ready || !running.compareAndSet(false,true)) return;
        try { projector.poll(Instant.now()); }
        catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(DiagnosticSchedule.class).warn("Diagnostic poll unavailable; failureType={}",failure.getClass().getSimpleName());
        } finally { running.set(false); }
    }
}
