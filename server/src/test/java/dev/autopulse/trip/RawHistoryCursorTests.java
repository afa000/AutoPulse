package dev.autopulse.trip;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.autopulse.shared.ApiFailure;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RawHistoryCursorTests {
    @Test void signedRawContextExpiresAndCannotBeUsedForDifferentQueryOrProcess() {
        Instant now=Instant.parse("2026-10-21T12:00:00Z"); var clock=mock(Clock.class); when(clock.instant()).thenReturn(now);
        var codec=new RawHistoryCursor(clock); UUID vehicle=UUID.randomUUID();
        var query=new HistoryRequest(now.minusSeconds(60),now,1,UUID.randomUUID(),null);
        var position=new RawHistoryCursor.Position(42,UUID.randomUUID()); String cursor=codec.encode(vehicle,query,position);
        assertThat(codec.decode(cursor,vehicle,query)).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode(cursor,UUID.randomUUID(),query)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> new RawHistoryCursor(clock).decode(cursor,vehicle,query)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> codec.decode(cursor,vehicle,new HistoryRequest(query.from(),query.to(),1,null,null))).isInstanceOf(ApiFailure.class);
        for (String invalid : new String[]{"", "x".repeat(2049),cursor+"=",(cursor.charAt(0)=='A' ? "B" : "A")+cursor.substring(1)})
            assertThatThrownBy(() -> codec.decode(invalid,vehicle,query)).isInstanceOf(ApiFailure.class);
        when(clock.instant()).thenReturn(now.plusSeconds(899)); assertThat(codec.decode(cursor,vehicle,query)).isEqualTo(position);
        when(clock.instant()).thenReturn(now.plusSeconds(900)); assertThatThrownBy(() -> codec.decode(cursor,vehicle,query)).isInstanceOf(ApiFailure.class);
    }
}
