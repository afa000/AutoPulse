package dev.autopulse.maintenance;

import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class MaintenanceRuleTests {
    final ObjectMapper mapper=JsonMapper.builder().build();
    ObjectNode observed(String date,String value,String unit) { return mapper.createObjectNode().put("observedAt",date+"T12:00:00Z").put("odometerValue",value).put("odometerUnit",unit); }
    @Test void independentlyWrittenOilBoundaryFixturesIncludingLeapAndMonthEndAgree() {
        var fixtures=mapper.readTree(Path.of("../contracts/examples/maintenance-rule-cases.json").toFile());var rule=new OilRule(mapper);
        for(var c:fixtures.get("oilCases")) {
            var profile=mapper.createObjectNode().put("applicability","US_2021_VENUE_G16").put("usageSchedule",c.get("schedule").asText())
                    .put("normalOilSpecificationConfirmed",true).put("timeZone","UTC").put("distanceUnit",c.get("unit").asText());
            var service=observed(c.get("serviceDate").asText(),c.get("serviceOdometer").asText(),c.get("unit").asText());
            var current=c.get("current").isNull()?null:observed(c.get("asOf").asText(),c.get("current").asText(),c.get("unit").asText());
            var result=rule.evaluate(profile,service,current,Instant.parse(c.get("asOf").asText()+"T12:00:00Z"));
            assertThat(result.get("state").asText()).as(c.get("name").asText()).isEqualTo(c.get("state").asText());
            assertThat(result.get("dueDate").asText()).isEqualTo(c.get("dueDate").asText());
        }
    }
    @Test void unknownProfileScheduleOilGradeServiceAndStaleOdometerAreNotReassuring() {
        var rule=new OilRule(mapper);var now=Instant.parse("2026-06-30T12:00:00Z");
        var p=mapper.createObjectNode().put("applicability","US_2021_VENUE_G16").put("usageSchedule","UNSET").put("normalOilSpecificationConfirmed",true).put("timeZone","America/New_York").put("distanceUnit","MI");
        var s=observed("2026-01-31","10000","MI");
        assertThat(rule.evaluate(null,s,s,now).get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(rule.evaluate(p,s,s,now).get("state").asText()).isEqualTo("UNKNOWN");p.put("usageSchedule","NORMAL").put("normalOilSpecificationConfirmed",false);
        assertThat(rule.evaluate(p,s,s,now).get("state").asText()).isEqualTo("UNKNOWN");p.put("normalOilSpecificationConfirmed",true);
        assertThat(rule.evaluate(p,null,s,now).get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(rule.evaluate(p,s,s,now).get("state").asText()).isEqualTo("UNKNOWN");
        var due=observed("2026-01-31","18000","MI");assertThat(rule.evaluate(p,s,due,now).get("state").asText()).isEqualTo("DUE");
    }
    @Test void selectedTimezoneControlsInclusiveCalendarBoundaryNotServerTimezone() {
        var rule=new OilRule(mapper);var p=mapper.createObjectNode().put("applicability","US_2021_VENUE_G16").put("usageSchedule","SEVERE")
                .put("normalOilSpecificationConfirmed",false).put("timeZone","America/Los_Angeles").put("distanceUnit","MI");
        var s=mapper.createObjectNode().put("observedAt","2025-09-01T06:30:00Z").put("odometerValue","10000").put("odometerUnit","MI");
        var before=rule.evaluate(p,s,null,Instant.parse("2026-02-28T07:59:59Z"));assertThat(before.get("dueDate").asText()).isEqualTo("2026-02-28");assertThat(before.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(rule.evaluate(p,s,null,Instant.parse("2026-02-28T08:00:00Z")).get("state").asText()).isEqualTo("DUE");
    }
    @Test void exactCrossUnitManualDistanceDoesNotBecomeTripEstimate() {
        var rule=new OilRule(mapper);var p=mapper.createObjectNode().put("applicability","US_2021_VENUE_G16").put("usageSchedule","NORMAL")
                .put("normalOilSpecificationConfirmed",true).put("timeZone","UTC").put("distanceUnit","MI");
        var s=observed("2026-01-01","0","KM");var current=observed("2026-01-02","12874.752","KM");
        assertThat(rule.evaluate(p,s,current,Instant.parse("2026-01-02T12:00:00Z")).get("state").asText()).isEqualTo("DUE");
    }
    @Test void handThermalFixtureOpensOnceUnknownDoesNotRecoverAndRecoveryIsExact() {
        var fixture=mapper.readTree(Path.of("../contracts/examples/synthetic-temperature-fixture.json").toFile());var rule=new TemperatureRule(mapper);JsonNode state=null;int opens=0,recovers=0;
        for(var r:fixture.get("readings")) {
            int offset=r.get("offsetSeconds").asInt();var input=thermal(offset,r.get("coolantTempC"));var d=rule.apply(state,input);state=d.state();
            if(d.action().equals("OPEN")){opens++;assertThat(offset).isEqualTo(32);}
            if(d.action().equals("RECOVER")){recovers++;assertThat(offset).isEqualTo(70);}
            if(offset==34){assertThat(d.action()).isEqualTo("UNKNOWN");assertThat(state.get("active").asBoolean()).isTrue();}
        }
        assertThat(opens).isEqualTo(1);assertThat(recovers).isEqualTo(1);assertThat(state.get("cycle").asLong()).isEqualTo(1);
    }
    ObjectNode thermal(int offset,JsonNode value) {
        String at=Instant.parse("2026-01-01T00:00:00Z").plusSeconds(offset).toString();var p=mapper.createObjectNode().put("capturedAt",at).put("receivedAt",at).put("readingId","r-"+offset);p.set("coolantTempC",value);return p;
    }
    @Test void thermalGapsStaleFutureAndHysteresisCannotFabricateContinuityOrRecovery() {
        var rule=new TemperatureRule(mapper);JsonNode state=null;
        for(int at:new int[]{0,2,4,30,32})state=rule.apply(state,thermal(at,mapper.valueToTree(120))).state();
        assertThat(state.get("active").asBoolean()).isFalse();
        var stale=thermal(34,mapper.valueToTree(120));stale.put("receivedAt","2026-01-01T00:10:00Z");
        assertThat(rule.apply(state,stale).action()).isEqualTo("UNKNOWN");
        var future=thermal(36,mapper.valueToTree(120));future.put("receivedAt","2026-01-01T00:00:35Z");assertThat(rule.apply(state,future).action()).isEqualTo("UNKNOWN");
        state=null;for(int at=0;at<=30;at+=2)state=rule.apply(state,thermal(at,mapper.valueToTree(120))).state();assertThat(state.get("active").asBoolean()).isTrue();
        state=rule.apply(state,thermal(32,mapper.valueToTree(95))).state();state=rule.apply(state,thermal(34,mapper.nullNode())).state();
        state=rule.apply(state,thermal(36,mapper.valueToTree(95))).state();assertThat(state.get("active").asBoolean()).isTrue();
        assertThat(rule.apply(state,thermal(38,mapper.valueToTree(95))).action()).isEqualTo("RECOVER");
    }
}
