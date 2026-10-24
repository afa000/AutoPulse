package dev.autopulse.trip;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

/** Strict public bounds; no precision rounding or server-local timezone parsing. */
public record HistoryRequest(Instant from,Instant to,int limit,UUID tripId,String cursor,String resolution) {
    public HistoryRequest(Instant from,Instant to,int limit,UUID tripId,String cursor) { this(from,to,limit,tripId,cursor,"raw"); }
    public HistoryRequest {
        if (resolution==null || !Set.of("raw","1m","5m").contains(resolution)) throw TripQueries.invalid("resolution must be raw, 1m or 5m");
        if (from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(resolution.equals("raw") ? Duration.ofHours(24) : Duration.ofDays(31))>0
                || from.getNano()%1000!=0 || to.getNano()%1000!=0 || limit<1 || limit>1000)
            throw TripQueries.invalid("History requires precise increasing bounds, raw <=24h or aggregate <=31 days, and limit 1..1000");
    }
    public static HistoryRequest parse(MultiValueMap<String,String> values) {
        var allowed=Set.of("from","to","limit","resolution","tripId","cursor");
        if (values.entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().size()!=1)) throw TripQueries.invalid("Unknown or repeated query parameter");
        String resolution=values.getFirst("resolution");
        if (resolution==null) resolution="raw";
        Instant from=time(values.getFirst("from")),to=time(values.getFirst("to"));
        String size=values.getFirst("limit");
        if (size!=null && !size.matches("[0-9]{1,4}")) throw TripQueries.invalid("limit must be an integer from 1 to 1000");
        int limit=size==null ? 200 : Integer.parseInt(size);
        if (limit<1 || limit>1000) throw TripQueries.invalid("limit must be 1..1000");
        String trip=values.getFirst("tripId"); UUID id=null;
        if (trip!=null) {
            if (!trip.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw TripQueries.invalid("tripId must be a UUID");
            id=UUID.fromString(trip);
        }
        return new HistoryRequest(from,to,limit,id,values.getFirst("cursor"),resolution);
    }
    private static Instant time(String text) {
        if (text==null || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,6})?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])"))
            throw TripQueries.invalid("from/to must be RFC 3339 timestamps with an offset and at most six fractional digits");
        try {
            boolean utc=text.endsWith("Z") || text.endsWith("z"); int end=text.length()-(utc ? 1 : 6);
            int offset=utc ? 0 : (Integer.parseInt(text.substring(end+1,end+3))*60+Integer.parseInt(text.substring(end+4)))*60*(text.charAt(end)=='-' ? -1 : 1);
            Instant result=LocalDateTime.parse(text.substring(0,end).toUpperCase(java.util.Locale.ROOT),DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC).minusSeconds(offset);
            int year=result.atOffset(ZoneOffset.UTC).getYear(); if (year<1 || year>9999) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) { throw TripQueries.invalid("from/to cannot be represented as UTC timestamps"); }
    }
}
