package dev.autopulse.live;

import dev.autopulse.telemetry.TelemetryRepository;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class LiveStreamsShutdownTests {
    @Test void shutdownDrainsQueuedWriterTasksRatherThanLeakingTheirReservations() throws Exception {
        var repository=mock(TelemetryRepository.class);when(repository.latest(any())).thenReturn(Optional.empty());
        var streams=new LiveStreams(repository);
        try {
            for(int group=0;group<8;group++) {UUID vehicle=UUID.randomUUID();for(int i=0;i<8;i++)streams.subscribe(vehicle,"fixture");}
            assertThat(streams.subscriptions()).isEqualTo(64);
            // Observe real queued drains rather than relying on a timing sleep.
            var writers=(java.util.concurrent.ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(streams,"writers");
            assertThat(writers).isNotNull();
            await().atMost(2,TimeUnit.SECONDS).untilAsserted(()->assertThat(writers.getQueue().size()).isGreaterThanOrEqualTo(4));
            streams.close();
            await().atMost(8,TimeUnit.SECONDS).untilAsserted(()->assertThat(streams.subscriptions()).isZero());
        } finally {streams.close();}
    }
}
