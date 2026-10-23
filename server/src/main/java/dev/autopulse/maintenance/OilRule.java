package dev.autopulse.maintenance;

import java.math.*;
import java.time.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Single pinned service rule, not a generic rule-expression interpreter. */
@Component
public class OilRule {
    public static final String VERSION="us-2021-venue-g16-oil-v1";
    private final ObjectMapper mapper;
    private final JsonNode provenance;
    public OilRule(ObjectMapper mapper) {
        this.mapper=mapper;
        try(var in=getClass().getResourceAsStream("/maintenance/oil-filter-v1.json")) { provenance=mapper.readTree(in); }
        catch(Exception e) { throw new IllegalStateException("Pinned oil rule unavailable",e); }
        if(!provenance.get("version").asText().equals(VERSION)) throw new IllegalStateException("Unreviewed rule version");
    }
    public ObjectNode evaluate(JsonNode profile,JsonNode service,JsonNode current,Instant now) {
        var e=mapper.createObjectNode();e.put("ruleVersion",VERSION).put("state","UNKNOWN").put("source","MANUAL");e.set("provenance",provenance.deepCopy());
        e.set("profile",profile==null?mapper.nullNode():profile.deepCopy());e.set("service",service==null?mapper.nullNode():service.deepCopy());
        e.set("currentOdometer",current==null?mapper.nullNode():current.deepCopy());e.put("evaluatedAt",now.toString());
        if(profile==null||!profile.get("applicability").asText().equals("US_2021_VENUE_G16")||profile.get("usageSchedule").asText().equals("UNSET")) return e.put("reason","APPLICABILITY_OR_SCHEDULE_UNKNOWN");
        if(profile.get("usageSchedule").asText().equals("NORMAL")&&!profile.get("normalOilSpecificationConfirmed").asBoolean()) return e.put("reason","OIL_SPECIFICATION_UNCONFIRMED");
        if(service==null) return e.put("reason","SERVICE_HISTORY_UNKNOWN");
        var zone=ZoneId.of(profile.get("timeZone").asText());var date=Instant.parse(service.get("observedAt").asText()).atZone(zone).toLocalDate();
        var schedule=provenance.get(profile.get("usageSchedule").asText().toLowerCase(java.util.Locale.ROOT));
        LocalDate due=date.plusMonths(schedule.get("months").asInt());String unit=profile.get("distanceUnit").asText();
        BigDecimal start=value(service,unit),distance=start.add(new BigDecimal(schedule.get(unit).asText()));
        e.put("dueDate",due.toString()).put("dueOdometerValue",distance.toPlainString()).put("odometerUnit",unit);
        boolean calendarDue=!now.atZone(zone).toLocalDate().isBefore(due);
        boolean stale=current==null||Instant.parse(current.get("observedAt").asText()).isBefore(now.minus(Duration.ofDays(30)));
        e.put("odometerFreshness",current==null?"UNKNOWN":stale?"STALE":"CURRENT");
        if(calendarDue) return e.put("state","DUE").put("reason","CALENDAR_BOUNDARY");
        if(current!=null&&value(current,unit).compareTo(distance)>=0) return e.put("state","DUE").put("reason","MANUAL_DISTANCE_BOUNDARY");
        return stale?e.put("reason","CURRENT_ODOMETER_UNKNOWN_OR_STALE"):e.put("state","NOT_DUE").put("reason","BELOW_BOTH_BOUNDARIES");
    }
    private BigDecimal value(JsonNode p,String unit) {
        var value=new BigDecimal(p.get("odometerValue").asText());String source=p.get("odometerUnit").asText();
        if(source.equals(unit)) return value;
        return source.equals("MI")?value.multiply(new BigDecimal("1.609344")):value.divide(new BigDecimal("1.609344"),18,RoundingMode.HALF_EVEN);
    }
}
