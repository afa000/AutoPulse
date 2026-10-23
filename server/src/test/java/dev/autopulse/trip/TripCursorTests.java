package dev.autopulse.trip;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import dev.autopulse.shared.ApiFailure;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripCursorTests {
    @Test void signedCursorBindsContextPreservesMicrosecondsAndExpiresAcrossTimeOrRestart() {
        Instant now=Instant.parse("2026-10-21T12:00:00Z"); var clock=mock(Clock.class); when(clock.instant()).thenReturn(now);
        var codec=new TripCursor(clock); UUID vehicle=UUID.randomUUID();
        var position=new TripCursor.Position(123,Instant.parse("2026-10-20T12:00:00.123456Z"),UUID.randomUUID());
        String cursor=codec.encode(vehicle,20,position);
        assertThat(codec.decode(cursor,vehicle,20)).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode(cursor,UUID.randomUUID(),20)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> codec.decode(cursor,vehicle,21)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> new TripCursor(clock).decode(cursor,vehicle,20)).isInstanceOf(ApiFailure.class);
        String changed=(cursor.charAt(0)=='A' ? "B" : "A")+cursor.substring(1);
        assertThatThrownBy(() -> codec.decode(changed,vehicle,20)).isInstanceOf(ApiFailure.class);
        for (String invalid : new String[]{"", "a".repeat(2049), "+++",cursor+"="})
            assertThatThrownBy(() -> codec.decode(invalid,vehicle,20)).isInstanceOf(ApiFailure.class);
        when(clock.instant()).thenReturn(now.plusSeconds(899)); assertThat(codec.decode(cursor,vehicle,20)).isEqualTo(position);
        when(clock.instant()).thenReturn(now.plusSeconds(900)); assertThatThrownBy(() -> codec.decode(cursor,vehicle,20)).isInstanceOf(ApiFailure.class);
    }
}
