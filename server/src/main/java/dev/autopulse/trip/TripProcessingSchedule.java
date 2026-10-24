package dev.autopulse.trip;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in startup/timer reconciliation, independent of HTTP and browser lifetime. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="autopulse.trip-processing.enabled",havingValue="true")
public class TripProcessingSchedule {
    private static final Logger LOG=LoggerFactory.getLogger(TripProcessingSchedule.class);
    private final TripProcessingCoordinator coordinator;
    private final int pageSize;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean ready;

    @Autowired
    public TripProcessingSchedule(TripProcessingCoordinator coordinator,@Value("${autopulse.trip-processing.page-size:100}") int pageSize,
            @Value("${autopulse.trip-processing.poll-delay-ms:1000}") long pollDelay) {
        if (pageSize<1 || pageSize>100) throw new IllegalArgumentException("trip page size must be 1..100");
        if (pollDelay<100 || pollDelay>60000) throw new IllegalArgumentException("trip poll delay must be 100..60000 ms");
        this.coordinator=coordinator; this.pageSize=pageSize;
    }

    public TripProcessingSchedule(TripProcessingCoordinator coordinator,int pageSize) { this(coordinator,pageSize,1000); }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { ready=true; }

    @EventListener(ContextClosedEvent.class)
    public void applicationStopped() { ready=false; }

    @Scheduled(fixedDelayString="${autopulse.trip-processing.poll-delay-ms:1000}",initialDelayString="${autopulse.trip-processing.poll-delay-ms:1000}")
    public void tick() {
        if (!ready || !running.compareAndSet(false,true)) return;
        try { coordinator.poll(pageSize); }
        catch (RuntimeException failure) {
            // Stage failures are recorded by the worker. Selection/reporting DB
            // outages must not stop later ticks or leak SQL/credentials in logs.
            LOG.warn("Trip processing poll could not complete; failureType={}",failure.getClass().getSimpleName());
        } finally { running.set(false); }
    }
}
