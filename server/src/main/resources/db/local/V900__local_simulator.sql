-- Deliberately opt-in local fixtures, excluded from the default migration locations.
INSERT INTO vehicle (id, public_id) VALUES
    ('10000000-0000-4000-8000-000000000001', 'venue-2021'),
    ('10000000-0000-4000-8000-000000000002', 'empty-vehicle');
INSERT INTO device (id, vehicle_id, public_id) VALUES
    ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'sim-device-001');
