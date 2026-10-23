# ADR 0028: preserve exact analytical JSON tokens in the browser

Date: 2026-10-22. Status: implemented and locally verified; awaiting owner review.

## Finding

Accepted finite individual measurements can produce an exact total larger than
binary64. A real 27-member short trip with coolant 1e308 returns sum 2.7e309,
yet its mean is finite 1e308. Native response.json() converts the sum to Infinity;
the trip/detail/page and aggregate parsers then reject valid evidence. Speed
integration can likewise have a large numerator with a finite kilometre ratio.
These extreme synthetic fixtures test a supported numeric contract, not realistic
vehicle temperatures/speeds or new physical plausibility claims.

## Decision and compatibility

Keep the existing JSON-number transport, exact backend/SQL calculations, schema,
storage, canonical telemetry, timestamps, original receipts, identities and opaque
cursors unchanged. No migration, sensor clipping, new sensor limits, decimal-string
wire variant or route version is introduced. Choose one coherent lossless parsing
boundary, not a mixture of string-valued and numeric API totals.

All successful/error trip and history HTTP responses are read as text by
useTripRequest, then parsed by pinned lossless-json 4.3.3. Numeric tokens named
sum/numerator/denominator remain LosslessNumber instances with their original
decimal spelling. The names correspond to the public analytical schema. Other
numeric fields remain display-only native numbers, subject to existing field
validation. Count/sampleCount/deviceCount/revision conversions additionally require
exact safe-integer equality; a fractional token rounding to an integer is invalid.
Raw telemetry is not re-encoded for ingestion/replay by this code. Full timestamp,
generation and cursor strings are never converted to numbers.

Parsers accept exact numeric-token objects (or finite native numbers for existing
in-memory fixtures/callers). JSON strings, forged plain objects, NaN/Infinity,
malformed JSON numbers, conflicting duplicate keys, unsafe/fractional metadata and invalid
field context remain rejected. An old caller that has already used JSON.parse
cannot recover a lost token; it must use the shared text boundary instead.
Non-finite native totals are still rejected, not silently trusted. Existing
last-known/error behavior remains when responses are genuinely invalid.
Identical duplicate values follow the parser's explicit coalescing policy; they
do not permit conflicting evidence to overwrite an earlier value.

Display division uses a private decimal.js 10.6.0 constructor with 40 significant
digits and half-even rounding. Exact source tokens are not altered. Only the
quotient is converted to binary64 for presentation/charts. This is a documented
display approximation, not exact arbitrary-precision calculation for replay.
Nonzero underflow and non-finite quotients are labeled Outside display range;
they are not converted to zero, confused with unavailable sensors, or allowed to
hide another valid trip. Null/count-zero still means no observations, supported
zero totals remain zero, signed totals/cancellation remain meaningful, and
distance denominators must be positive.

Trip detail exposes unrounded totals/counts and distance ratio in an accessible
expandable section; aggregate history retains all metric totals and exposes them
alongside its accessible values table. Out-of-range aggregate means are identified
explicitly rather than silently displayed as missing observations. Extreme finite
values use readable scientific notation. Charts scale their axes by an explicit
power of ten for large/small magnitudes so plotting arithmetic does not overflow;
tables remain unscaled, and no interpolation/coverage semantics change.

## Verification

A permanent React HTTP-response regression was written first and observed failing:
a valid 4e308 total prevented the sensor-summary table from loading. It now passes
without changing the response token or weakening validation. Shared numerical
fixtures cover signed totals, cancellation, zero/null and an overflowing distance
numerator with a finite ratio. All ordinary shared trip/history examples also pass
through the actual text parser. Malformed tokens, conflicting duplicate keys, forged values,
underflow/overflow display handling and metadata boundaries have focused tests.

NumericAnalyticsDemo owns a fresh PostgreSQL container/random loopback server and
uses actual HTTP ingestion for 31 short-commute readings per vehicle, replaying
each batch. Each resulting trip has 27 members. Coolant totals are +2.7e309,
-2.7e309 and +1e308 after fourteen positive/thirteen negative trip observations.
All speed inputs are 1e308; the exact speed integral is 5.2e309, divided by 3600
for a finite estimated kilometre value. The backend checks these independent
totals before Chromium reads its real list/detail/raw/1m/5m responses.

The browser imports the same production parsing functions for all three vehicles
and inspects the actual mounted production UI for the positive vehicle. No API
response is mocked or fulfilled. Both trip-filtered history and the opt-in owned
unfiltered aggregate adapter are covered. Explicit test-only retention archives,
verifies and prunes 27 eligible rows per vehicle; summaries, exact totals, finite
means and distance must survive. Raw becomes EXPIRED while retained and owned
1m/5m results stay equal. Desktop/narrow screenshots and axe checks are required.
The saved owned-reader/retention/processing flags are unchanged; no owner DB is used.

The new demo is wired into the existing optional local browser check and CI web
job. Wiring is not proof of a new hosted run. Record actual passing executions and
limitations in checkpoint notes; pause for owner review before diagnostics work.

## Dependencies and limits

Both libraries are MIT-licensed and pinned in the lockfile. Official references:
[lossless-json](https://github.com/josdejong/lossless-json),
[decimal.js API](https://mikemcl.github.io/decimal.js/).
The chosen APIs were checked against these primary references and installed type
definitions. No global runtime/tool changes are needed. Browser numbers remain
display approximations; this change does not promise exact binary64 min/max,
plots or raw sensor display, nor change the existing telemetry display interface.
Persisted/canonical evidence and wire numeric tokens remain exact. Existing
bounded pages/ranges, request cancellation and timeout policies remain in force.
