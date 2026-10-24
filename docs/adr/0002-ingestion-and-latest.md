# ADR 0002: First telemetry ingestion and latest-reading semantics

## Status

Accepted for the private local development slice.

## Decision

- Public vehicle identifiers remain strings such as `venue-2021`. Vehicle and device tables use internal UUID primary keys; ingestion resolves the public IDs and verifies the device belongs to that vehicle. Fixture registration is explicit and local/test only.
- A batch is `{ "readings": [...] }`, with 1-100 independently validated items and at most 262144 UTF-8 bytes. Malformed JSON/envelopes return request-level 400, oversized input 413, and unsupported media types 415. A valid envelope returns 200, one original-index result per item, and accepted/duplicate/rejected counts that sum to its length.
- An item is rejected for invalid data, unknown vehicle/device, mismatched association, or changed content under an existing identity. Valid neighbors still persist. Errors identify JSON fields. Systemic database failures roll back the batch and return retriable 503; they are never permanent item rejections.
- PostgreSQL enforces unique `(device_id, boot_id, sequence_number)` using the internal device reference. `INSERT ... ON CONFLICT DO NOTHING` avoids aborting the transaction on duplicates. The following lookup compares canonical JSONB content: optional missing/null fields are equivalent, UUIDs and UTC times are normalized, and numerically equivalent JSON numbers compare equal. An exact resend is a duplicate; changed content is `IDENTITY_CONFLICT` and never overwrites the original.
- Latest orders by `recorded_at DESC, device_id DESC, boot_id DESC, sequence_number DESC`. The UUID tie-breakers are deterministic rather than claims of chronological order across boots. Receipt time is separate and cannot promote a delayed old reading. A known vehicle without data returns 204; an unknown vehicle returns 404.
- Timestamps are normalized to UTC and stored in PostgreSQL `timestamptz`. Sensor columns remain nullable; latest returns the selected whole reading without carrying old sensor values forward.
- Request errors and batch results carry a generated UUID correlation ID, also returned in `X-Correlation-Id`. Authentication and user ownership are deferred; the server binds to loopback by default and is intended only for private local development.

## Consequences

Retries can keep their original payload and identity. The server can acknowledge valid parts of a mixed batch without forcing resends. The database remains the final concurrency guard. Durable delivery, production identity management, time-skew policy, and telemetry retention are later work.
