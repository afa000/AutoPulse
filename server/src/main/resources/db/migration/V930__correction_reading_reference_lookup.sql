-- Support bounded selected-reading reference inspection without expanding or
-- scanning every correction replay. This scans existing reference UUID keys and
-- takes normal index/DDL locks; no payload scan, historical validation or pruning.
CREATE INDEX trip_correction_reading_lookup ON trip_correction_reading(reading_id,replay_id);

-- All original raw/durable FKs and immutable/exact-original guards remain.
