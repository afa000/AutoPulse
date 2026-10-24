-- Trip-filtered history fixes primary device and seeks by the full native tuple.
CREATE INDEX telemetry_device_history ON telemetry_reading(vehicle_id,device_id,recorded_at,boot_id,sequence_number);
