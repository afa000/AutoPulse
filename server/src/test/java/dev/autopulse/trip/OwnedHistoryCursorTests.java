package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedHistoryCursorTests {
    final Instant now=Instant.parse("2026-10-21T12:00:00Z");
    final UUID vehicle=UUID.randomUUID();
    final HistoryRequest query=new HistoryRequest(now.minusSeconds(120).plusNanos(1000),now.plusNanos(1000),1,null,null,"1m");
    final OwnedHistoryCursor.Position position=new OwnedHistoryCursor.Position(42,now.minusSeconds(60));
    @Test void contextRoundTripsAndExpiresAtFifteenMinutes() {
        var clock=mock(Clock.class); when(clock.instant()).thenReturn(now); var codec=new OwnedHistoryCursor(clock);
        String token=codec.encode(vehicle,query,position); assertThat(token).hasSize(142);
        assertThat(codec.decode(token,vehicle,query)).isEqualTo(position);
        when(clock.instant()).thenReturn(now.plusSeconds(899)); assertThat(codec.decode(token,vehicle,query)).isEqualTo(position);
        when(clock.instant()).thenReturn(now.plusSeconds(900)); assertThatThrownBy(() -> codec.decode(token,vehicle,query)).isInstanceOf(ApiFailure.class);
    }
    @Test void everyFilterAndVehicleIsBoundAndRawOrTripQueriesAreRejected() {
        var codec=new OwnedHistoryCursor(Clock.fixed(now,ZoneOffset.UTC)); String token=codec.encode(vehicle,query,position);
        assertThatThrownBy(() -> codec.decode(token,UUID.randomUUID(),query)).isInstanceOf(ApiFailure.class);
        for (HistoryRequest changed : new HistoryRequest[]{
                new HistoryRequest(query.from().plusNanos(1000),query.to(),1,null,null,"1m"),
                new HistoryRequest(query.from(),query.to().plusNanos(1000),1,null,null,"1m"),
                new HistoryRequest(query.from(),query.to(),2,null,null,"1m"),
                new HistoryRequest(query.from(),query.to(),1,null,null,"5m"),
                new HistoryRequest(query.from(),query.to(),1,UUID.randomUUID(),null,"1m"),
                new HistoryRequest(query.from(),query.to(),1,null,null,"raw")})
            assertThatThrownBy(() -> codec.decode(token,vehicle,changed)).isInstanceOf(ApiFailure.class);
    }
    @Test void tamperingNoncanonicalOtherProcessAndOldCursorKindsAreRejected() {
        var clock=Clock.fixed(now,ZoneOffset.UTC); var codec=new OwnedHistoryCursor(clock); String token=codec.encode(vehicle,query,position);
        for (String bad : new String[]{"",token+"=","x".repeat(2049),(token.charAt(0)=='A'?"B":"A")+token.substring(1),
                new RawHistoryCursor(clock).encode(vehicle,query,new RawHistoryCursor.Position(42,UUID.randomUUID()))})
            assertThatThrownBy(() -> codec.decode(bad,vehicle,query)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> new OwnedHistoryCursor(clock).decode(token,vehicle,query)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> new RawHistoryCursor(clock).decode(token,vehicle,query)).isInstanceOf(ApiFailure.class);
    }
    @Test void invalidBoundariesAndGenerationsCannotBeIssued() {
        var codec=new OwnedHistoryCursor();
        for (var bad : new OwnedHistoryCursor.Position[]{new OwnedHistoryCursor.Position(-1,position.after()),
                new OwnedHistoryCursor.Position(1,query.from()),new OwnedHistoryCursor.Position(1,query.to()),
                new OwnedHistoryCursor.Position(1,position.after().plusNanos(1000)),new OwnedHistoryCursor.Position(1,null)})
            assertThatThrownBy(() -> codec.encode(vehicle,query,bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futureIssueTimeIsRejectedAndNegativeEpochBoundsRemainExact() {
        var clock=mock(Clock.class); when(clock.instant()).thenReturn(now); var codec=new OwnedHistoryCursor(clock);
        String token=codec.encode(vehicle,query,position);
        when(clock.instant()).thenReturn(now.minusSeconds(1)); assertThatThrownBy(() -> codec.decode(token,vehicle,query)).isInstanceOf(ApiFailure.class);
        var old=new HistoryRequest(Instant.parse("1969-12-31T23:59:59.999999Z"),Instant.parse("1970-01-01T00:05:00.000001Z"),1,null,null,"5m");
        var anchor=new OwnedHistoryCursor.Position(9,Instant.EPOCH);
        assertThat(codec.decode(codec.encode(vehicle,old,anchor),vehicle,old)).isEqualTo(anchor);
    }
}
