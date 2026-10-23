package dev.autopulse.diagnostic;

import dev.autopulse.shared.ApiFailure;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Strict bounded v1 decoder; future/association checks belong to original acceptance. */
@Component
public class DiagnosticValidator {
    public static final int MAX_BYTES=65536;
    public static final List<String> CATEGORIES=List.of("PENDING","CONFIRMED","PERMANENT");
    public static final Set<String> OUTCOMES=Set.of("COMPLETE","PARTIAL","UNSUPPORTED","UNAVAILABLE","TIMEOUT","FAILED");
    public record Snapshot(String vehicle,String device,UUID boot,long sequence,Instant captured,String source,ObjectNode payload) {}
    private final ObjectMapper mapper;
    public DiagnosticValidator(ObjectMapper mapper) { this.mapper=mapper; }
    public Snapshot decode(byte[] bytes) {
        if(bytes.length>MAX_BYTES) throw new ApiFailure(413,"REQUEST_TOO_LARGE","Diagnostic request exceeds 65536 bytes",List.of());
        try {
            String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\uFEFF")) throw invalid();
            JsonNode node=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
            return validate(node);
        } catch(ApiFailure failure) { throw failure; }
        catch(java.nio.charset.CharacterCodingException failure) { throw invalid(); }
        catch(RuntimeException failure) { throw invalid(); }
    }
    public Snapshot validate(JsonNode value) {
        try {
            shape(value,"snapshotVersion deviceId vehicleId bootId diagnosticSequence capturedAt source ecus");
            if(!value.get("snapshotVersion").isIntegralNumber() || !value.get("snapshotVersion").canConvertToInt() || value.get("snapshotVersion").asInt()!=1) throw invalid();
            String device=text(value,"deviceId"),vehicle=text(value,"vehicleId"),boot=text(value,"bootId"),time=text(value,"capturedAt"),source=text(value,"source");
            if(!device.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") || !vehicle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
                    || !boot.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z") || time.startsWith("0000")
                    || !Set.of("SIMULATED","OBD").contains(source)) throw invalid();
            // LocalDateTime rejects invalid calendar dates/24:00/leap seconds, rather than Instant's permissive normalization.
            Instant captured=java.time.LocalDateTime.parse(time.substring(0,time.length()-1)).toInstant(java.time.ZoneOffset.UTC);
            var seq=value.get("diagnosticSequence");
            if(!seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue()<0) throw invalid();
            var ecus=value.get("ecus"); if(!ecus.isArray() || ecus.size()<1 || ecus.size()>8) throw invalid();
            String previous="";
            for(var ecu:ecus) {
                shape(ecu,"ecuId outcome mil categories");
                String id=text(ecu,"ecuId"),overall=outcome(ecu);
                if(!id.matches("[A-Z][A-Z0-9_-]{0,31}") || id.compareTo(previous)<=0) throw invalid(); previous=id;
                var mil=ecu.get("mil"); shape(mil,"outcome illuminated"); String mo=outcome(mil);
                if(mo.equals("PARTIAL") || (mo.equals("COMPLETE") ? !mil.get("illuminated").isBoolean() : !mil.get("illuminated").isNull())) throw invalid();
                List<String> outcomes=new ArrayList<>(List.of(mo)); var categories=ecu.get("categories");
                if(!categories.isArray() || categories.size()!=3) throw invalid();
                for(int i=0;i<3;i++) {
                    var category=categories.get(i); shape(category,"category outcome codes"); String co=outcome(category); outcomes.add(co);
                    if(!text(category,"category").equals(CATEGORIES.get(i))) throw invalid();
                    var codes=category.get("codes"); if(!codes.isArray() || codes.size()>32) throw invalid();
                    if(!Set.of("COMPLETE","PARTIAL").contains(co) && !codes.isEmpty()) throw invalid();
                    String last="";
                    for(var code:codes) {
                        if(!code.isString() || !code.asText().matches("[PBCU][0-3][0-9A-F]{3}") || code.asText().compareTo(last)<=0) throw invalid();
                        last=code.asText();
                    }
                }
                if(overall.equals("COMPLETE")!=outcomes.stream().allMatch("COMPLETE"::equals)
                        || !Set.of("COMPLETE","PARTIAL").contains(overall) && outcomes.stream().anyMatch(o->!o.equals(overall))) throw invalid();
            }
            ObjectNode canonical=((ObjectNode)value).deepCopy(); canonical.put("capturedAt",captured.toString());
            return new Snapshot(vehicle,device,UUID.fromString(boot),seq.longValue(),captured,source,canonical);
        } catch(ApiFailure failure) { throw failure; }
        catch(RuntimeException failure) { throw invalid(); }
    }
    private static void shape(JsonNode node,String fields) {
        var keys=fields.split(" "); if(node==null || !node.isObject() || node.size()!=keys.length) throw invalid();
        for(String key:keys) if(!node.has(key)) throw invalid();
    }
    private static String text(JsonNode node,String name) { var v=node.get(name); if(v==null || !v.isString()) throw invalid(); return v.asText(); }
    private static String outcome(JsonNode node) { String v=text(node,"outcome"); if(!OUTCOMES.contains(v)) throw invalid(); return v; }
    static ApiFailure invalid() { return new ApiFailure(400,"INVALID_REQUEST","Invalid bounded diagnostic snapshot v1",List.of()); }
}
