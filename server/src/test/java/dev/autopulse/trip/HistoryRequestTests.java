package dev.autopulse.trip;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

class HistoryRequestTests {
    @Test void aggregateDensityCapNeverAllowsTruncatedStatistics() {
        HistoryQueries.checkAggregateSize(100000);
        assertThatThrownBy(() -> HistoryQueries.checkAggregateSize(100001)).isInstanceOf(dev.autopulse.shared.ApiFailure.class)
                .hasMessageContaining("No partial summary");
    }
    @Test void timestampNormalizationSupportsRfcOffsetsAndMicrosecondsWithoutRounding() {
        var query=new LinkedMultiValueMap<String,String>();
        query.add("from","2026-10-21t11:59:00.123456+23:59"); query.add("to","2026-10-21T12:00:00.123456+23:59");
        var value=HistoryRequest.parse(query); assertThat(value.from()).isEqualTo(Instant.parse("2026-10-20T12:00:00.123456Z"));
        assertThat(value.to()).isEqualTo(value.from().plusSeconds(60)); assertThat(value.limit()).isEqualTo(200);
        for (String invalid : new String[]{"2026-02-29T12:00:00Z","2026-10-20T12:00:60Z","2026-10-20T12:00:00.1234567Z","0001-01-01T00:00:00+23:59"}) {
            query.set("from",invalid); assertThatThrownBy(() -> HistoryRequest.parse(query)).isInstanceOf(dev.autopulse.shared.ApiFailure.class);
        }
    }
}
