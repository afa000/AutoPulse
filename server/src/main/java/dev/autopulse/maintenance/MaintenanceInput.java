package dev.autopulse.maintenance;

import dev.autopulse.shared.ApiFailure;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Small strict manual command boundary; decimal-string odometers never pass through double. */
@Component
public class MaintenanceInput {
    private final ObjectMapper mapper;
    public MaintenanceInput(ObjectMapper mapper) { this.mapper=mapper; }
    public ObjectNode decode(byte[] bytes) {
        if(bytes.length>8192) throw new ApiFailure(413,"REQUEST_TOO_LARGE","Maintenance command exceeds 8192 UTF-8 bytes",List.of());
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\uFEFF")) throw invalid();
            JsonNode value=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
            if(!value.isObject()) throw invalid(); return (ObjectNode)value;
        } catch(ApiFailure e) { throw e; } catch(Exception e) { throw invalid(); }
    }
    public void shape(JsonNode p,String fields) {
        var expected=new HashSet<>(List.of(fields.split(" ")));var actual=new HashSet<String>();p.propertyNames().forEach(actual::add);
        if(!actual.equals(expected)) throw invalid();
    }
    public String text(JsonNode p,String field) { var v=p.get(field);if(v==null||!v.isString()) throw invalid();return v.asText(); }
    public String choice(JsonNode p,String field,String... choices) { String value=text(p,field);if(!List.of(choices).contains(value)) throw invalid();return value; }
    public UUID request(JsonNode p) { return uuid(text(p,"requestId")); }
    public UUID uuid(String value) { if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();return UUID.fromString(value); }
    public long revision(JsonNode p) { var v=p.get("expectedRevision");if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<0) throw invalid();return v.asLong(); }
    public String bounded(JsonNode p,String field,int max,boolean nullable) {
        var v=p.get(field);if(v==null) throw invalid(); if(nullable&&v.isNull()) return null;
        String s=text(p,field);if(s.isBlank()||s.length()>max||s.chars().anyMatch(c->Character.isISOControl(c))) throw invalid();return s;
    }
    public Instant time(JsonNode p,String field) {
        String s=text(p,field);try {
            if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")||s.startsWith("0000")) throw invalid();
            return LocalDateTime.parse(s.substring(0,s.length()-1)).toInstant(ZoneOffset.UTC);
        } catch(RuntimeException e) { throw invalid(); }
    }
    public BigDecimal odometer(JsonNode p) {
        String s=text(p,"odometerValue");if(!s.matches("(0|[1-9][0-9]{0,6})(\\.[0-9]{1,3})?")) throw invalid();
        BigDecimal value=new BigDecimal(s);return choice(p,"odometerUnit","MI","KM").equals("MI")?value.multiply(new BigDecimal("1.609344")):value;
    }
    public static ApiFailure invalid() { return new ApiFailure(400,"MAINTENANCE_INVALID","Invalid bounded maintenance command or query",List.of()); }
    public static ApiFailure conflict(String code) { return new ApiFailure(409,code,"Maintenance history changed or command identity conflicts; inspect current evidence",List.of()); }
}
