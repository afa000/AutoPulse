import json
from pathlib import Path

from autopulse_agent.models.source import SampleSource

def test_example_source_is_simulated():

    repo_root = Path(__file__).resolve().parents[2]
    example_path = repo_root / "contracts" / "examples" / "telemetry-sample.json"
    data = json.loads(example_path.read_text(encoding="utf-8"))
    assert SampleSource(data["source"]) is SampleSource.SIMULATED

