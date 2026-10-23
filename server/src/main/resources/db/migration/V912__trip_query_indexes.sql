CREATE INDEX trip_public_list ON trip_record(vehicle_id,started_at DESC,id DESC) WHERE status IN ('ACTIVE','COMPLETED');
CREATE INDEX trip_original_reference ON trip_publication_original(trip_id);
CREATE INDEX trip_replacement_reference ON trip_correction_lineage(parent_trip_id,child_trip_id) WHERE relation='SUPERSEDED';
