package dev.autopulse.live;

import dev.autopulse.telemetry.TelemetryRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LiveStreams {
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("live-write-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("live-coordinate").factory());
    // Completion can block on Spring's emitter lock AND on response flushing.
    // Keep it off both the coordinator and the independent network writer pool.
    private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("live-close-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final LiveHub hub;

    public LiveStreams(TelemetryRepository repository) {
        hub = new LiveHub(repository::latest, writers, cleanup, System::nanoTime);
        coordinator.scheduleWithFixedDelay(hub::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    @TransactionalEventListener
    public void committed(TelemetryCommitted event) { event.vehicles().forEach(hub::committed); }

    public SseEmitter subscribe(UUID vehicle, String slug) {
        LiveEmitter emitter = new LiveEmitter();
        LiveHub.Client client = hub.subscribe(vehicle, slug, new LiveHub.Sink() {
            public void send(LiveHub.Frame frame) throws Exception {
                // A request aborted before MVC attaches the response must not
                // accumulate invisible early-send buffers until the lifetime cap.
                if (!emitter.awaitResponse()) throw new java.io.IOException("Stream response was not attached");
                if (frame.event() == null) emitter.send(SseEmitter.event().comment(frame.data()));
                else emitter.send(SseEmitter.event().name(frame.event()).data(frame.data(), org.springframework.http.MediaType.APPLICATION_JSON));
            }
            public void close() { emitter.retire(); }
            public boolean awaitingContainerCompletion() { return emitter.awaitingContainerCompletion(); }
        });
        emitter.onCompletion(() -> { emitter.containerClosing();client.transportComplete(); });
        emitter.onTimeout(() -> { emitter.containerClosing();client.close(); });
        emitter.onError(error -> { emitter.containerClosing();client.close(); });
        return emitter;
    }

    public int subscriptions() { return hub.subscriptions(); }

    @PreDestroy public void close() {
        coordinator.shutdownNow();
        hub.close();
        // Run already-queued (now closed) drains so each releases its reservation.
        // shutdownNow discards them with draining=true forever; response attachment wait is bounded.
        writers.shutdown();
        cleanup.shutdown();
    }
}
