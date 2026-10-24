"""Local structural checks, not a claim of hosted Actions execution."""

from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]


def test_workflow_targets_declared_checks_without_local_secrets_or_skips():
    raw = (ROOT / ".github/workflows/verify.yml").read_text()
    workflow = yaml.safe_load(raw)
    assert set(workflow["on"]) == {"push", "pull_request", "workflow_dispatch"}
    assert workflow["permissions"] == {"contents": "read"}
    python = workflow["jobs"]["python"]
    assert python["strategy"]["matrix"]["python-version"] == ["3.12", "3.14"]
    assert any(step.get("run") == "python -m pytest device-agent/tests" for step in python["steps"])
    backend = workflow["jobs"]["java-postgres"]
    assert any(step.get("run") == "bash ./mvnw --batch-mode test" for step in backend["steps"])
    assert "secrets." not in raw and ".env" not in raw and "skipTests" not in raw
    assert (ROOT / "scripts/verify.ps1").is_file()
    web = workflow["jobs"]["web"]
    assert any(step.get("run") == "npm ci" for step in web["steps"])
    assert any("DashboardBrowserDemo" in step.get("run", "") for step in web["steps"])
    assert any("playwright install --with-deps chromium" in step.get("run", "") for step in web["steps"])
