package dev.autopulse.maintenance;

import java.math.BigDecimal;
import java.time.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Exact observations only, deliberately SIMULATED and not a manufacturer threshold. */
public final class TemperatureRule {
    public static final String VERSION="synthetic-temperature-v1";
    public record Decision(ObjectNode state,String action) {}
    private final ObjectMapper mapper;
    public TemperatureRule(ObjectMapper mapper) { this.mapper=mapper; }
    public Decision apply(JsonNode prior,JsonNode observation) {
        var s=prior==null?mapper.createObjectNode().put("active",false).put("cycle",0):((ObjectNode)prior).deepCopy();
        Instant captured=Instant.parse(observation.get("capturedAt").asText()),received=Instant.parse(observation.get("receivedAt").asText());
        Instant previous=instant(s,"previousAt");boolean continuous=previous!=null&&!captured.isBefore(previous)&&Duration.between(previous,captured).compareTo(Duration.ofSeconds(5))<=0;
        s.put("previousAt",captured.toString());
        boolean qualified=!captured.isAfter(received)&&!captured.isBefore(received.minusSeconds(300));
        var value=observation.get("coolantTempC");
        if(!qualified||value==null||value.isNull()) {
            s.putNull("candidateAt").putNull("recoveryAt").put("uncertain",true);
            return new Decision(s,"UNKNOWN");
        }
        BigDecimal temp=value.decimalValue();s.put("uncertain",false);
        if(s.get("active").asBoolean()) {
            if(!continuous)s.putNull("recoveryAt");
            if(temp.compareTo(new BigDecimal("95"))<=0) {
                if(instant(s,"recoveryAt")==null)s.put("recoveryAt",captured.toString());
                if(Duration.between(instant(s,"recoveryAt"),captured).compareTo(Duration.ofSeconds(2))>=0) {
                    s.put("active",false).putNull("candidateAt").putNull("recoveryAt");return new Decision(s,"RECOVER");
                }
            } else s.putNull("recoveryAt");
            if(temp.compareTo(new BigDecimal(s.get("peakC").asText()))>0)s.put("peakC",temp.toPlainString());
            return new Decision(s,"CONTINUING");
        }
        if(temp.compareTo(new BigDecimal("110"))<0) { s.putNull("candidateAt");return new Decision(s,"BELOW"); }
        if(!continuous||instant(s,"candidateAt")==null) {
            s.put("candidateAt",captured.toString()).put("candidateReadingId",observation.get("readingId").asText()).put("peakC",temp.toPlainString());
        } else if(temp.compareTo(new BigDecimal(s.get("peakC").asText()))>0)s.put("peakC",temp.toPlainString());
        if(Duration.between(instant(s,"candidateAt"),captured).compareTo(Duration.ofSeconds(30))>=0) {
            s.put("active",true).put("cycle",Math.addExact(s.get("cycle").asLong(),1));return new Decision(s,"OPEN");
        }
        return new Decision(s,"CANDIDATE");
    }
    private static Instant instant(JsonNode p,String field) { var n=p.get(field);return n==null||n.isNull()?null:Instant.parse(n.asText()); }
}
