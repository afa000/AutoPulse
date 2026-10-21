package dev.autopulse.live;

import dev.autopulse.telemetry.TelemetryRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class LiveHubTests {
    final UUID vehicle = UUID.randomUUID();
    final AtomicLong now = new AtomicLong();
    final AtomicReference<Optional<TelemetryRepository.Latest>> latest = new AtomicReference<>(Optional.empty());
    static class Sink implements LiveHub.Sink {
        final List<LiveHub.Frame> frames = new ArrayList<>();
        boolean closed;
        public void send(LiveHub.Frame frame) { frames.add(frame); }
        public void close() { closed = true; }
    }
    TelemetryRepository.Latest sample(int sequence) {
        return new TelemetryRepository.Latest(JsonMapper.builder().build().readTree("{\"sequenceNumber\":" + sequence + "}"), "2026-10-20T12:00:00.123456Z");
    }

    @Test void emptyFirstReadingUnchangedStateReconnectAndHeartbeat() {
        try (var hub = new LiveHub(id -> latest.get(), Runnable::run, Runnable::run, now::get)) {
            Sink sink = new Sink();
            var client = hub.subscribe(vehicle, "test\"slug", sink);
            hub.tick();
            assertThat(sink.frames.getFirst().event()).isEqualTo("empty");
            assertThat(sink.frames.getFirst().data()).contains("test\\\"slug");
            latest.set(Optional.of(sample(1))); hub.committed(vehicle); hub.tick();
            assertThat(sink.frames).hasSize(2);
            hub.committed(vehicle); hub.tick(); // duplicate/older authoritative state
            assertThat(sink.frames).hasSize(2);
            now.set(TimeUnit.SECONDS.toNanos(15)); hub.tick();
            assertThat(sink.frames.getLast().event()).isNull();
            client.close();
            Sink reconnected = new Sink();
            hub.subscribe(vehicle, "v", reconnected); hub.tick();
            assertThat(reconnected.frames.getFirst().data()).contains("123456Z");
            assertThat(hub.subscriptions()).isEqualTo(1);
        }
    }

    @Test void commitDuringSnapshotCannotBeMissedAndPendingSnapshotCoalesces() {
        var queued = new ArrayDeque<Runnable>();
        var holder = new AtomicReference<LiveHub>();
        latest.set(Optional.of(sample(1)));
        AtomicLong reads = new AtomicLong();
        try (var hub = new LiveHub(id -> {
            var snapshot = latest.get();
            if (reads.getAndIncrement() == 0) {
                latest.set(Optional.of(sample(2)));
                holder.get().committed(id); // commit while initial query is completing
            }
            return snapshot;
        }, queued::add, Runnable::run, now::get)) {
            holder.set(hub);
            Sink sink = new Sink(); hub.subscribe(vehicle, "v", sink);
            hub.tick(); hub.tick();
            assertThat(queued).hasSize(1); // one drain task, not a queue per event
            queued.remove().run();
            assertThat(sink.frames).hasSize(1);
            assertThat(sink.frames.getFirst().data()).contains("\"sequenceNumber\":2");
        }
    }

    @Test void concurrentNotificationsOnlyReadAuthoritativeStateAndVehiclesAreIndependent() throws Exception {
        try (var hub = new LiveHub(id -> id.equals(vehicle) ? latest.get() : Optional.empty(), Runnable::run, Runnable::run, now::get)) {
            Sink one = new Sink(), two = new Sink();
            hub.subscribe(vehicle, "one", one); hub.subscribe(UUID.randomUUID(), "two", two); hub.tick();
            latest.set(Optional.of(sample(99)));
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < 20; i++) threads.add(Thread.startVirtualThread(() -> hub.committed(vehicle)));
            for (Thread thread : threads) thread.join();
            hub.tick();
            assertThat(one.frames).hasSize(2); assertThat(two.frames).hasSize(1);
            assertThat(one.frames.getLast().data()).contains("99");
        }
    }

    @Test void capacityTimeoutDisconnectAndWriterRejectionAreBounded() {
        try (var hub = new LiveHub(id -> latest.get(), Runnable::run, Runnable::run, now::get)) {
            for (int v = 0; v < 8; v++) {
                UUID id = UUID.randomUUID();
                for (int i = 0; i < 8; i++) hub.subscribe(id, "v", new Sink());
                assertThatThrownBy(() -> hub.subscribe(id, "v", new Sink())).isInstanceOf(RejectedExecutionException.class);
            }
            assertThat(hub.subscriptions()).isEqualTo(64);
            assertThatThrownBy(() -> hub.subscribe(vehicle, "v", new Sink())).isInstanceOf(RejectedExecutionException.class);
            now.set(TimeUnit.SECONDS.toNanos(120)); hub.tick();
            assertThat(hub.subscriptions()).isZero();
        }
        try (var hub = new LiveHub(id -> latest.get(), task -> { throw new RejectedExecutionException(); }, Runnable::run, now::get)) {
            Sink sink = new Sink(); hub.subscribe(vehicle, "v", sink); hub.tick();
            assertThat(hub.subscriptions()).isZero(); assertThat(sink.closed).isTrue();
        }
    }

    @Test void slowWriterIsRetiredWithoutBlockingCoordinatorOrOtherClients() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        try (var hub = new LiveHub(id -> latest.get(), task -> threads.add(Thread.startVirtualThread(task)), Runnable::run, now::get)) {
            var client = hub.subscribe(vehicle, "v", new LiveHub.Sink() {
                public void send(LiveHub.Frame frame) throws Exception { entered.countDown(); release.await(); }
                public void close() {}
            });
            hub.tick(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            now.set(TimeUnit.SECONDS.toNanos(6)); hub.tick();
            // Keep the capacity reservation until the in-flight writer returns.
            assertThat(hub.subscriptions()).isEqualTo(1);
            client.close(); assertThat(hub.subscriptions()).isEqualTo(1);
            release.countDown();
            for (Thread thread : threads) thread.join(2000);
            assertThat(hub.subscriptions()).isZero();
        } finally { release.countDown(); for (Thread thread : threads) thread.join(2000); }
    }

    @Test void databaseFailureAndBrokenSinkReleaseSubscriptions() {
        try (var hub = new LiveHub(id -> { throw new IllegalStateException("database down"); }, Runnable::run, Runnable::run, now::get)) {
            hub.subscribe(vehicle, "v", new Sink()); hub.tick(); assertThat(hub.subscriptions()).isZero();
        }
        try (var hub = new LiveHub(id -> latest.get(), Runnable::run, Runnable::run, now::get)) {
            hub.subscribe(vehicle, "v", new LiveHub.Sink() {
                public void send(LiveHub.Frame frame) throws Exception { throw new java.io.IOException("closed"); }
                public void close() {}
            });
            hub.tick(); assertThat(hub.subscriptions()).isZero();
        }
    }

    @Test void springEmitterBlockedCloseCannotStallOtherVehicleUpdatesOrLeaseCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1), completed = new CountDownLatch(1);
        // Use Spring's actual protected write lock, not a no-op close double.
        class StalledEmitter extends SseEmitter {
            void stalledSend() throws Exception {
                writeLock.lock();
                try { entered.countDown(); release.await(); }
                finally { writeLock.unlock(); }
            }
        }
        var emitter = new StalledEmitter();
        var writers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        var cleanup = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        var coordinator = Executors.newSingleThreadExecutor();
        var hub = new LiveHub(id -> latest.get(), writers, cleanup, now::get);
        var goodFrames = new CopyOnWriteArrayList<LiveHub.Frame>();
        UUID other = UUID.randomUUID();
        try {
            hub.subscribe(vehicle, "blocked", new LiveHub.Sink() {
                public void send(LiveHub.Frame frame) throws Exception { emitter.stalledSend(); }
                public void close() { closing.countDown(); emitter.complete(); completed.countDown(); }
            });
            hub.subscribe(other, "healthy", new LiveHub.Sink() {
                public void send(LiveHub.Frame frame) { goodFrames.add(frame); }
                public void close() {}
            });
            coordinator.submit(hub::tick).get(2, TimeUnit.SECONDS);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(goodFrames).hasSize(1));
            now.set(TimeUnit.SECONDS.toNanos(6));
            coordinator.submit(hub::tick).get(2, TimeUnit.SECONDS);
            assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.getCount()).isEqualTo(1); // Spring complete is still waiting for send's lock.
            assertThat(hub.subscriptions()).isEqualTo(2); // stalled transport retains its resource slot
            latest.set(Optional.of(sample(2))); hub.committed(other);
            coordinator.submit(hub::tick).get(2, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(goodFrames).hasSize(2));
            assertThat(goodFrames.getLast().data()).contains("\"sequenceNumber\":2");
            now.set(TimeUnit.SECONDS.toNanos(121));
            coordinator.submit(hub::tick).get(2, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(hub.subscriptions()).isEqualTo(1));
            release.countDown();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(hub.subscriptions()).isZero());
        } finally {
            release.countDown(); hub.close();
            coordinator.shutdownNow(); writers.shutdownNow(); cleanup.shutdownNow();
            assertThat(writers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void blockedCleanupRetainsCapacityAndRejectedCleanupRetriesWithoutRunningInline() {
        var queued = new ArrayDeque<Runnable>();
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var hub = new LiveHub(id -> latest.get(), Runnable::run, task -> {
            if (reject.get()) throw new RejectedExecutionException();
            queued.add(task);
        }, now::get)) {
            var sinks = new ArrayList<Sink>();
            for (int group = 0; group < 8; group++) {
                UUID id = group == 0 ? vehicle : UUID.randomUUID();
                for (int i = 0; i < 8; i++) {
                    Sink sink = new Sink(); sinks.add(sink);
                    hub.subscribe(id, "v", sink).close();
                }
            }
            assertThat(hub.subscriptions()).isEqualTo(64);
            assertThatThrownBy(() -> hub.subscribe(vehicle, "v", new Sink())).isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> hub.subscribe(UUID.randomUUID(), "v", new Sink())).isInstanceOf(RejectedExecutionException.class);
            assertThat(sinks).allMatch(sink -> !sink.closed);
            reject.set(false); hub.tick(); hub.tick();
            assertThat(queued).hasSize(64); // one cleanup task per retiring transport
            while (!queued.isEmpty()) queued.remove().run();
            assertThat(hub.subscriptions()).isZero();
            assertThat(sinks).allMatch(sink -> sink.closed);
        }
    }
}
