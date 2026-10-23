package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Authenticated history context and row anchor. Resolution-specific kinds prevent cross-use. */
@Component
public class RawHistoryCursor {
    public record Position(long generation,UUID readingId) {}
    private final byte[] key=new byte[32];
    private final Clock clock;
    public RawHistoryCursor() { this(Clock.systemUTC()); }
    public RawHistoryCursor(Clock clock) { this.clock=clock; new SecureRandom().nextBytes(key); }
    public String encode(UUID vehicle,HistoryRequest query,Position position) {
        var data=ByteBuffer.allocate(95).put((byte)1).put(kind(query)); uuid(data,vehicle);
        data.put((byte)(query.tripId()==null ? 0 : 1)); uuid(data,query.tripId()==null ? new UUID(0,0) : query.tripId());
        data.putInt(query.limit()); time(data,query.from()); time(data,query.to()); data.putLong(position.generation()); uuid(data,position.readingId()); data.putLong(clock.instant().getEpochSecond());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(127).put(data.array()).put(sign(data.array())).array());
    }
    public Position decode(String value,UUID vehicle,HistoryRequest query) {
        try {
            if (value==null || value.length()>2048 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if (bytes.length!=127 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw new IllegalArgumentException();
            byte[] payload=Arrays.copyOf(bytes,95);
            if (!MessageDigest.isEqual(sign(payload),Arrays.copyOfRange(bytes,95,127))) throw new IllegalArgumentException();
            var data=ByteBuffer.wrap(payload);
            if (data.get()!=1 || data.get()!=kind(query) || !uuid(data).equals(vehicle)) throw new IllegalArgumentException();
            int filtered=data.get(); UUID trip=uuid(data);
            if (filtered!=(query.tripId()==null ? 0 : 1) || !trip.equals(query.tripId()==null ? new UUID(0,0) : query.tripId())
                    || data.getInt()!=query.limit() || !time(data).equals(query.from()) || !time(data).equals(query.to())) throw new IllegalArgumentException();
            long generation=data.getLong(); UUID reading=uuid(data); long issued=data.getLong(),now=clock.instant().getEpochSecond();
            if (generation<0 || issued>now || now-issued>=900) throw new IllegalArgumentException();
            return new Position(generation,reading);
        } catch (IllegalArgumentException invalid) { throw new ApiFailure(400,"INVALID_CURSOR","History cursor is invalid or expired; restart paging",List.of()); }
    }
    private byte[] sign(byte[] payload) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(payload); }
        catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("History cursor signing unavailable",failure); }
    }
    private static byte kind(HistoryRequest query) { return switch(query.resolution()) { case "raw" -> 2; case "1m" -> 3; case "5m" -> 4; default -> throw new IllegalArgumentException(); }; }
    private static void uuid(ByteBuffer data,UUID value) { data.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()); }
    private static UUID uuid(ByteBuffer data) { return new UUID(data.getLong(),data.getLong()); }
    private static void time(ByteBuffer data,Instant value) { data.putLong(value.getEpochSecond()).putInt(value.getNano()); }
    private static Instant time(ByteBuffer data) { long seconds=data.getLong(); int nanos=data.getInt(); if (nanos<0 || nanos>999999000 || nanos%1000!=0) throw new IllegalArgumentException(); return Instant.ofEpochSecond(seconds,nanos); }
}
