# 0003: Timestamp precision and exact numeric validation

Status: accepted (2026-10-20).

Telemetry timestamps accept zero to six fractional second digits. Python datetime
and PostgreSQL timestamptz both preserve microseconds, so finer wire precision is
rejected with a recordedAt error, not truncated or rounded. UTC normalization
preserves the instant and accepts lowercase t/z and RFC offsets through 23:59.
The normalized instant must remain in years 0001-9999. Calendar and offset
components are validated independently. Leap seconds are not supported.

Latest still orders by recorded_at then the documented identity tie-breakers.
Accepted readings one microsecond apart cannot collapse to a database tie.
Canonical timestamps can omit trailing zeroes; this does not change their instant
or exact-retry equivalence. Event identity remains device/boot/sequence.

Sensor bounds use the original JSON decimal before double conversion. The server
parses floating tokens as BigDecimal; the Python wire codec uses Decimal with
simplejson (one runtime dependency) so validation and replay do not silently round
100.0000000000000001 to 100. Models retain exact decimals; ordinary native floats
are already approximate and cannot recover a submitted token. Canonical JSON
retains exact values, so different decimals still cause identity conflicts even
when their approximate sensor columns coincide. Never clamp values to bounds.

Approximate sensor columns require finite doubles and reject nonzero values that
underflow to zero. JSONB numeric storage also limits fractional digits to 16383
and integer digits to 131072; unsupported numbers are item errors, not database
outages. Null, zero, false and valid negative temperatures keep their meanings.
These storage limits are additional semantic validation beyond OpenAPI number
bounds. Timestamp UTC overflow is likewise checked by both implementations.

A read-only inspection of the existing development database found four readings
and zero recordedAt payloads with more than six fractional digits. No migration,
data deletion, column or latest-query change is required. A different database
containing legacy finer-precision payloads requires explicit compatibility review;
this change does not rewrite historical data or promise replay of invalid input.
