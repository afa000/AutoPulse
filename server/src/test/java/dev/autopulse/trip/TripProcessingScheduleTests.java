package dev.autopulse.trip;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripProcessingScheduleTests {
    @Test void disabledByDefaultAndExplicitlyEnabledOnly() {
        var coordinator=mock(TripProcessingCoordinator.class);
        var context=new ApplicationContextRunner().withUserConfiguration(TripProcessingSchedule.class)
                .withBean(TripProcessingCoordinator.class,() -> coordinator);
        context.run(app -> assertThat(app).doesNotHaveBean(TripProcessingSchedule.class));
        context.withPropertyValues("autopulse.trip-processing.enabled=false").run(app -> assertThat(app).doesNotHaveBean(TripProcessingSchedule.class));
        context.withPropertyValues("autopulse.trip-processing.enabled=true","autopulse.trip-processing.poll-delay-ms=60000")
                .run(app -> assertThat(app).hasSingleBean(TripProcessingSchedule.class));
        verifyNoInteractions(coordinator);
    }

    @Test void waitsForApplicationReadinessAndPollsOneConfiguredPage() {
        var coordinator=mock(TripProcessingCoordinator.class); var schedule=new TripProcessingSchedule(coordinator,7);
        schedule.tick(); verifyNoInteractions(coordinator);
        schedule.applicationReady(); schedule.tick(); verify(coordinator).poll(7); verifyNoMoreInteractions(coordinator);
        schedule.applicationStopped(); schedule.tick(); verifyNoMoreInteractions(coordinator);
        assertThatThrownBy(() -> new TripProcessingSchedule(coordinator,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripProcessingSchedule(coordinator,1,0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pollingFailureDoesNotDisableLaterTicks() {
        var coordinator=mock(TripProcessingCoordinator.class);
        when(coordinator.poll(1)).thenThrow(new IllegalStateException("private details")).thenReturn(null);
        var schedule=new TripProcessingSchedule(coordinator,1); schedule.applicationReady();
        assertThatCode(schedule::tick).doesNotThrowAnyException(); schedule.tick(); verify(coordinator,times(2)).poll(1);
    }

    @Test void overlappingLocalTicksDoNotStartParallelPages() throws Exception {
        var coordinator=mock(TripProcessingCoordinator.class); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(coordinator.poll(1)).thenAnswer(call -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return null; });
        var schedule=new TripProcessingSchedule(coordinator,1); schedule.applicationReady();
        var first=CompletableFuture.runAsync(schedule::tick);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); schedule.tick(); verify(coordinator).poll(1); }
        finally { release.countDown(); }
        first.get(5,TimeUnit.SECONDS); schedule.tick(); verify(coordinator,times(2)).poll(1);
    }
}
