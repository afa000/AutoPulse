package dev.autopulse.live;

import dev.autopulse.telemetry.TelemetryRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** One coordinator owns database reads; network writers never run on ingestion. */
public final class LiveHub implements AutoCloseable {
    public record Frame(String event, String data) {}
    public interface Sink {
        void send(Frame frame) throws Exception;
        void close();
    }
    private static final long SECOND = 1_000_000_000L;
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private final Function<UUID, Optional<TelemetryRepository.Latest>> latest;
    private final Executor writers;
    private final Executor cleanup;
    private final LongSupplier clock;
    private volatile boolean stopped;

    private static class State {
        final ConcurrentHashMap<Client, Boolean> clients = new ConcurrentHashMap<>();
        final AtomicBoolean dirty = new AtomicBoolean(true);
    }

    public LiveHub(Function<UUID, Optional<TelemetryRepository.Latest>> latest, Executor writers, Executor cleanup, LongSupplier clock) {
        this.latest = latest; this.writers = writers; this.cleanup = cleanup; this.clock = clock;
    }

    public synchronized Client subscribe(UUID vehicle, String slug, Sink sink) {
        if (stopped || count.get() >= 64) throw new RejectedExecutionException("live subscription capacity reached");
        AtomicReference<Client> result = new AtomicReference<>();
        states.compute(vehicle, (key, existing) -> {
            State state = existing == null ? new State() : existing;
            synchronized (state) {
                if (state.clients.size() >= 8) throw new RejectedExecutionException("vehicle subscription capacity reached");
                Client client = new Client(state, slug, sink);
                count.incrementAndGet();
                state.clients.put(client, true);
                state.dirty.set(true);
                result.set(client);
            }
            return state;
        });
        return result.get();
    }

    public void committed(UUID vehicle) {
        State state = states.get(vehicle);
        if (state != null) state.dirty.set(true);
    }

    /** Called serially by a fixed scheduler, or manually with controlled clocks in tests. */
    public void tick() {
        for (var entry : states.entrySet()) {
            UUID vehicle = entry.getKey();
            State state = entry.getValue();
            synchronized (state) {
                long now = clock.getAsLong();
                for (Client client : state.clients.keySet()) {
                    if (client.closed.get()) client.scheduleCleanup();
                    else if (now - client.created >= 120 * SECOND ||
                            (client.writing.get() && now - client.writeStarted >= 5 * SECOND)) client.close();
                }
                if (state.clients.keySet().stream().anyMatch(client -> !client.closed.get()) && state.dirty.getAndSet(false)) {
                    try {
                        Optional<TelemetryRepository.Latest> snapshot = latest.apply(vehicle);
                        for (Client client : state.clients.keySet()) {
                            if (client.closed.get()) continue;
                            String json = snapshot.map(value -> "{\"reading\":" + value.reading()
                                    + ",\"receivedAt\":\"" + value.receivedAt() + "\"}")
                                    .orElse("{\"vehicleId\":" + quote(client.slug) + "}");
                            if (!json.equals(client.lastSnapshot)) {
                                client.lastSnapshot = json;
                                client.offer(new Frame(snapshot.isPresent() ? "telemetry" : "empty", json));
                            }
                        }
                    } catch (RuntimeException error) {
                        for (Client client : state.clients.keySet()) client.close();
                    }
                }
                for (Client client : state.clients.keySet()) {
                    if (!client.closed.get() && now - client.lastHeartbeat >= 15 * SECOND) {
                        client.lastHeartbeat = now;
                        client.pending.compareAndSet(null, new Frame(null, "keepalive"));
                        client.drain();
                    }
                }
            }
            // Registration also holds the map key during insertion; pruning cannot orphan a subscriber.
            states.computeIfPresent(vehicle, (key, current) -> current == state && current.clients.isEmpty() ? null : current);
        }
    }

    private static String quote(String value) {
        // Public IDs are arbitrary JSON strings, not safe for direct interpolation.
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
    }

    /** Includes retiring transports until their write and cleanup have both returned. */
    public int subscriptions() { return count.get(); }

    public synchronized void close() {
        stopped = true;
        for (State state : states.values()) for (Client client : state.clients.keySet()) client.close();
        states.clear();
    }

    public final class Client implements AutoCloseable {
        private final State state;
        private final String slug;
        private final Sink sink;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean writing = new AtomicBoolean();
        private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
        private final AtomicBoolean cleanupFinished = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicReference<Frame> pending = new AtomicReference<>();
        private final long created = clock.getAsLong();
        private volatile long writeStarted;
        private long lastHeartbeat = created;
        private String lastSnapshot;

        private Client(State state, String slug, Sink sink) { this.state = state; this.slug = slug; this.sink = sink; }
        private void offer(Frame frame) { pending.set(frame); drain(); }
        private void drain() {
            if (closed.get() || !draining.compareAndSet(false, true)) return;
            try { writers.execute(this::write); }
            catch (RejectedExecutionException full) { draining.set(false); close(); releaseIfFinished(); }
        }
        private void write() {
            try {
                Frame frame;
                while (!closed.get() && (frame = pending.getAndSet(null)) != null) {
                    writeStarted = clock.getAsLong();
                    writing.set(true);
                    try { sink.send(frame); }
                    finally { writing.set(false); }
                }
            } catch (Exception disconnected) { close(); }
            finally {
                draining.set(false);
                if (closed.get()) releaseIfFinished();
                else if (pending.get() != null) drain();
            }
        }
        public void close() {
            if (closed.compareAndSet(false, true)) {
                pending.set(null);
                scheduleCleanup();
            }
        }
        private void scheduleCleanup() {
            if (!cleanupScheduled.compareAndSet(false, true)) return;
            try {
                cleanup.execute(() -> {
                    try { sink.close(); }
                    catch (RuntimeException alreadyTerminated) { /* response may already be complete */ }
                    finally { cleanupFinished.set(true); releaseIfFinished(); }
                });
            } catch (RejectedExecutionException full) {
                // Never complete inline: completion can wait for Spring's write lock
                // or flush a blocked socket. Retain capacity and retry on a later tick.
                cleanupScheduled.set(false);
            }
        }
        private void releaseIfFinished() {
            if (closed.get() && cleanupFinished.get() && !draining.get() && released.compareAndSet(false, true)) {
                state.clients.remove(this);
                count.decrementAndGet();
            }
        }
    }
}
