package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Fixed-size authenticated list cursor. Process-local key deliberately expires on restart. */
@Component
public class TripCursor {
    public record Position(long generation,Instant startedAt,UUID tripId) {}
    private final byte[] key=new byte[32];
    private final Clock clock;
    public TripCursor() { this(Clock.systemUTC()); }
    public TripCursor(Clock clock) { this.clock=clock; new SecureRandom().nextBytes(key); }
    public String encode(UUID vehicle,int limit,Position position) {
        var payload=ByteBuffer.allocate(66).put((byte)1).put((byte)1);
        uuid(payload,vehicle); payload.putInt(limit).putLong(position.generation()).putLong(position.startedAt().getEpochSecond())
                .putInt(position.startedAt().getNano()); uuid(payload,position.tripId()); payload.putLong(clock.instant().getEpochSecond());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(98).put(payload.array()).put(sign(payload.array())).array());
    }
    public Position decode(String value,UUID vehicle,int limit) {
        try {
            if (value==null || value.length()>2048 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if (bytes.length!=98 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw new IllegalArgumentException();
            byte[] payload=java.util.Arrays.copyOf(bytes,66);
            if (!MessageDigest.isEqual(sign(payload),java.util.Arrays.copyOfRange(bytes,66,98))) throw new IllegalArgumentException();
            var data=ByteBuffer.wrap(payload);
            if (data.get()!=1 || data.get()!=1 || !uuid(data).equals(vehicle) || data.getInt()!=limit) throw new IllegalArgumentException();
            long generation=data.getLong(),seconds=data.getLong(); int nanos=data.getInt(); UUID trip=uuid(data); long issued=data.getLong();
            long now=clock.instant().getEpochSecond();
            if (generation<0 || nanos<0 || nanos>999999000 || nanos%1000!=0 || issued>now || now-issued>=900) throw new IllegalArgumentException();
            return new Position(generation,Instant.ofEpochSecond(seconds,nanos),trip);
        } catch (IllegalArgumentException error) { throw new ApiFailure(400,"INVALID_CURSOR","Cursor is invalid or expired; restart paging",List.of()); }
    }
    private byte[] sign(byte[] payload) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(payload); }
        catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Cursor signing unavailable",failure); }
    }
    private static void uuid(ByteBuffer data,UUID id) { data.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID uuid(ByteBuffer data) { return new UUID(data.getLong(),data.getLong()); }
}
