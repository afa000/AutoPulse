package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Process-local signed aggregate boundary, deliberately incompatible with raw-row cursors. */
@Component
public class OwnedHistoryCursor {
    public record Position(long generation,Instant after) {}
    private final byte[] key=new byte[32];
    private final Clock clock;
    public OwnedHistoryCursor() { this(Clock.systemUTC()); }
    public OwnedHistoryCursor(Clock clock) { this.clock=clock; new SecureRandom().nextBytes(key); }
    public String encode(UUID vehicle,HistoryRequest query,Position position) {
        validate(query,position);
        var data=ByteBuffer.allocate(74).put((byte)1).put(kind(query));
        data.putLong(vehicle.getMostSignificantBits()).putLong(vehicle.getLeastSignificantBits()).putInt(query.limit());
        time(data,query.from()); time(data,query.to()); data.putLong(position.generation()); time(data,position.after());
        data.putLong(clock.instant().getEpochSecond());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(106).put(data.array()).put(sign(data.array())).array());
    }
    public Position decode(String value,UUID vehicle,HistoryRequest query) {
        try {
            if (value==null || value.length()>2048 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if (bytes.length!=106 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw new IllegalArgumentException();
            byte[] payload=Arrays.copyOf(bytes,74);
            if (!MessageDigest.isEqual(sign(payload),Arrays.copyOfRange(bytes,74,106))) throw new IllegalArgumentException();
            var data=ByteBuffer.wrap(payload);
            if (data.get()!=1 || data.get()!=kind(query) || !new UUID(data.getLong(),data.getLong()).equals(vehicle)
                    || data.getInt()!=query.limit() || !time(data).equals(query.from()) || !time(data).equals(query.to()))
                throw new IllegalArgumentException();
            var position=new Position(data.getLong(),time(data)); validate(query,position);
            long issued=data.getLong(),now=clock.instant().getEpochSecond();
            if (issued>now || now-issued>=900) throw new IllegalArgumentException();
            return position;
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new ApiFailure(400,"INVALID_CURSOR","Owned aggregate cursor is invalid or expired; restart paging",List.of());
        }
    }
    private static byte kind(HistoryRequest q) {
        if (q.tripId()!=null || q.resolution().equals("raw")) throw new IllegalArgumentException("Expected unfiltered aggregate cursor");
        return (byte)(q.resolution().equals("1m")?5:6);
    }
    private static void validate(HistoryRequest q,Position p) {
        kind(q); int seconds=q.resolution().equals("1m")?60:300;
        if (p==null || p.generation()<0 || p.after()==null || p.after().getNano()!=0 || Math.floorMod(p.after().getEpochSecond(),seconds)!=0
                || !p.after().isAfter(q.from()) || !p.after().isBefore(q.to())) throw new IllegalArgumentException("Invalid aggregate page boundary");
    }
    private byte[] sign(byte[] data) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(data); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Owned history signing unavailable",e); }
    }
    private static void time(ByteBuffer data,Instant value) { data.putLong(value.getEpochSecond()).putInt(value.getNano()); }
    private static Instant time(ByteBuffer data) {
        long seconds=data.getLong(); int nano=data.getInt();
        if (nano<0 || nano>999999000 || nano%1000!=0) throw new IllegalArgumentException();
        return Instant.ofEpochSecond(seconds,nano);
    }
}
