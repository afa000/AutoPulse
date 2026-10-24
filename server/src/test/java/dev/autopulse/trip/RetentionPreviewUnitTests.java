package dev.autopulse.trip;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetentionPreviewUnitTests {
    @Test void disabledDoesNotReadClockDatabaseOrOpenTransaction() {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class); var clock=mock(Clock.class);
        var preview=new RetentionPreview(jdbc,manager,new RetentionPreview.Options(false,true,30,96),clock);
        var page=preview.report(UUID.randomUUID(),100,null);
        assertThat(page.state()).isEqualTo("DISABLED"); assertThat(page.readings()).isEmpty(); assertThat(page.wouldDelete()).isZero();
        assertThat(page.asOf()).isNull(); assertThat(page.nextCursor()).isNull();
        assertThat(page.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("NOT_EVALUATED",0,0,0,0));
        var barrier=preview.reportUnderBarrier(UUID.randomUUID(),100,null);
        assertThat(barrier.state()).isEqualTo("DISABLED"); assertThat(barrier.preview().wouldDelete()).isZero();
        assertThat(barrier.archiveChecks()).isEmpty();
        assertThat(barrier.referenceChecks()).isEmpty();
        assertThat(preview.prunePage(UUID.randomUUID(),100,null).state()).isEqualTo("DISABLED");
        verifyNoInteractions(jdbc,manager,clock);
    }
    @Test void unsafeModesAndUnsupportedBoundsFailClosed() {
        assertThat(new RetentionPreview.Options(false,false,30,96).enabled()).isFalse();
        for (int days : new int[]{0,6,3651}) assertThatThrownBy(() -> new RetentionPreview.Options(true,true,days,96)).isInstanceOf(IllegalArgumentException.class);
        for (int hours : new int[]{0,95,8761}) assertThatThrownBy(() -> new RetentionPreview.Options(true,true,30,hours)).isInstanceOf(IllegalArgumentException.class);
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
        var preview=new RetentionPreview(jdbc,manager,new RetentionPreview.Options(false,true,30,96),Clock.systemUTC());
        for (int size : new int[]{0,101}) assertThatThrownBy(() -> preview.report(UUID.randomUUID(),size,null)).isInstanceOf(IllegalArgumentException.class);
        for (int size : new int[]{0,101}) assertThatThrownBy(() -> preview.reportUnderBarrier(UUID.randomUUID(),size,null)).isInstanceOf(IllegalArgumentException.class);
        for (int size : new int[]{0,101}) assertThatThrownBy(() -> preview.prunePage(UUID.randomUUID(),size,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc,manager);
    }
}
