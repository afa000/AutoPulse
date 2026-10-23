"""Pure fixture/safety checks for the explicit disposable milestone demo."""

import importlib.util
from pathlib import Path
import subprocess
import sys
from uuid import UUID


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/demo_trips.py"


def test_demo_rows_preserve_nulls_boot_identity_and_fresh_templates():
    spec = importlib.util.spec_from_file_location("trip_demo_fixture", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    boot = UUID("aaaaaaaa-0000-4000-8000-000000000001")
    rows = list(module.finite_rows("edge-vehicle", [(0, 1000, 36), (2, None, None, boot)]))
    assert rows[0]["rpm"] == 1000 and rows[0]["speedKph"] == 36
    assert rows[1]["rpm"] is rows[1]["speedKph"] is None
    assert rows[0]["bootId"] != rows[1]["bootId"] == str(boot)
    assert rows[0]["vehicleId"] == rows[1]["vehicleId"] == "edge-vehicle"
    assert rows[0]["sequenceNumber"] == 0 and rows[1]["sequenceNumber"] == 1
    assert rows[0]["mafGps"] is rows[1]["mafGps"] is None


def test_demo_refuses_non_loopback_target_before_network_or_capture_creation(tmp_path):
    captures = tmp_path / "must-not-exist"
    result = subprocess.run([sys.executable, str(SCRIPT), "--server-url", "https://example.invalid",
                             "--captures", str(captures), "--mode", "seed", "--isolated-demo"],
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == 2
    assert "JUnit-owned loopback URL" in result.stderr
    assert not captures.exists()
