"""Cross-runtime smoke demonstration, launched against disposable infra by JUnit.

Never clears a database. Refuses a target with existing latest telemetry.
"""

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.request import Request, urlopen

from autopulse_agent import json_codec as json
from autopulse_agent.capture import FORMAT, capture_batches
from autopulse_agent.send import replay_capture, send_source
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from autopulse_agent.transport.http import BatchSender, HttpBatchClient, MAX_BYTES


def demonstrate(server_url, capture_path):
    latest_url = server_url + "/api/v1/vehicles/venue-2021/telemetry/latest"
    with urlopen(latest_url, timeout=5) as response:
        assert response.status == 204, "Refusing demo: target already has readings"

    forwarded = []

    class InterruptedAcknowledgment(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_POST(self):
            size = int(self.headers["Content-Length"])
            assert 0 < size <= MAX_BYTES
            raw = self.rfile.read(size)
            forwarded.append(raw)
            request = Request(server_url + self.path, data=raw, headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=10) as response:
                acknowledgment = response.read(MAX_BYTES + 1)
                assert len(acknowledgment) <= MAX_BYTES
            self.send_response(200)
            if len(forwarded) == 1:
                # The real backend has committed. Cut off the chunked ACK.
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                partial = b'{"accepted":'
                self.wfile.write(f"{len(partial):x}\r\n".encode() + partial + b"\r\n")
                self.wfile.flush()
                self.close_connection = True
            else:
                self.send_header("Content-Length", str(len(acknowledgment)))
                self.end_headers()
                self.wfile.write(acknowledgment)

    proxy = ThreadingHTTPServer(("127.0.0.1", 0), InterruptedAcknowledgment)
    thread = Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    try:
        client = HttpBatchClient(f"http://127.0.0.1:{proxy.server_port}")
        source = SimulatedTelemetrySource("sim-device-001", "venue-2021",
            datetime(2026, 10, 20, 12, tzinfo=timezone.utc), scenario="normal-commute")
        with capture_path.open("xb") as capture:
            capture.write(json.dumps({"format": FORMAT}).encode() + b"\n")
            delivered = send_source(source, BatchSender(client, sleep=lambda _: None),
                interval=0.01, batch_size=100, capture=capture,
                clock=lambda: 0, sleep=lambda _: None)
    finally:
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)
    assert len(forwarded) == 4 and forwarded[0] == forwarded[1]
    assert asdict(delivered) == {"accepted": 151, "duplicate": 100, "rejected": 0}

    sender = BatchSender(HttpBatchClient(server_url), sleep=lambda _: None)
    with urlopen(latest_url, timeout=5) as response:
        original_latest = json.loads(response.read(MAX_BYTES + 1))
    with capture_path.open("rb") as capture:
        replay = replay_capture(capture, sender)
    assert asdict(replay) == {"accepted": 0, "duplicate": 251, "rejected": 0}
    with capture_path.open("rb") as capture:
        batches = list(capture_batches(capture))  # bounded finite fixture for assertions only
    assert [len(batch) for batch in batches] == [100, 100, 51]
    assert batches[0][0]["rpm"] == 0 and batches[0][0]["mafGps"] is None
    assert any(item["milOn"] is False for batch in batches for item in batch)
    assert original_latest["reading"] == batches[-1][-1]
    assert original_latest["reading"]["recordedAt"] == "2026-10-20T12:08:20Z"

    older = dict(batches[0][0], sequenceNumber=251, recordedAt="2026-10-20T11:59:59Z", milOn=False)
    assert sender.deliver([older]).accepted == 1
    left = dict(older, sequenceNumber=252)
    invalid = dict(older, sequenceNumber=254, engineLoadPct=json.loads("100.0000000000000001"))
    right = dict(older, sequenceNumber=253)
    mixed = sender.deliver([left, invalid, right])
    assert asdict(mixed) == {"accepted": 2, "duplicate": 0, "rejected": 1}
    with urlopen(latest_url, timeout=5) as response:
        assert json.loads(response.read(MAX_BYTES + 1)) == original_latest
    report = {"delivery": asdict(delivered), "replay": asdict(replay), "mixed": asdict(mixed),
        "batches": [100, 100, 51], "expectedRows": 254, "latestSequence": 250,
        "identicalRetryBytes": True, "latestAndReceiptUnchanged": True}
    print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--capture", type=Path, required=True)
    args = parser.parse_args()
    demonstrate(args.server_url, args.capture)
