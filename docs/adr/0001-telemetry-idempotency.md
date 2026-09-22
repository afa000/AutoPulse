# ADR 0001: Telemetry idempotency key

## Status

Accepted

## Context

The device agent may send the same telemetry reading more than once when a network request fails or its response is lost. The server needs a way to recognize a retry without storing a duplicate reading.

A sequence number increases during one run of the device agent, but it may restart at zero when the agent starts again. Therefore, `deviceId` and `sequenceNumber` alone cannot uniquely identify a reading over time.

## Decision

Each telemetry reading will include `deviceId`, `bootId`, and `sequenceNumber`.

The agent generates a new UUID for `bootId` when it starts. It uses that same ID for every reading collected during that run, while `sequenceNumber` increases for each reading.

The server will treat the combination of `deviceId`, `bootId`, and `sequenceNumber` as the reading's idempotency key. A database unique constraint will enforce this rule.

If a reading is stored in the local outbox for a later retry, its original `bootId` and `sequenceNumber` must be stored with it. Restarting the agent must not change the identity of an already collected reading.

## Consequences

- Retrying a reading will not create a second database record.
- Sequence numbers can restart when the agent starts a new run.
- Readings can be ordered by sequence number within one agent run.
- The agent must generate a boot ID and retain each queued reading's original identifiers.
- The database must enforce uniqueness across the three fields.
