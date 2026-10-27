"""Finite single-source collector and independent, oldest-first upload worker."""

import math
import threading
import time

from .. import json_codec
from ..models.diagnostics import snapshot_to_json
from ..models.telemetry_json import sample_to_payload
from .diagnostic_upload import DiagnosticUploader
from .telemetry_upload import TelemetryUploader
from .store import CapacityError, MAX_PAYLOAD_BYTES, OutboxError


class WorkerStillRunning(RuntimeError):
    pass


def run(store, *, source=None, upload=False, pace=2.0, drain_seconds=10.0,
        max_readings=1000, stop=None, telemetry_uploader=None, diagnostic_uploader=None):
    if (type(pace) not in (int, float) or not math.isfinite(pace) or not 0.001 <= pace <= 3600 or
            type(drain_seconds) not in (int, float) or not math.isfinite(drain_seconds) or not 0 <= drain_seconds <= 60 or
            type(max_readings) is not int or not 1 <= max_readings <= 10000):
        raise ValueError("pace must be 0.001..3600 seconds, drain timeout 0..60, and max readings 1..10000")
    stop = stop or threading.Event()
    shutdown = threading.Event()
    work_ready = threading.Event()
    errors = []
    worker = None
    readings = diagnostics = 0
    completed = source is None
    paused = False

    def wait_for_room(size, *, exact=False):
        nonlocal paused
        # Preflight uses an upper bound before reading an unknown observation.
        # Once encoded, reserve its actual size without clipping an impossible fit.
        reservation = size if exact else min(size, store.policy.payload_bytes)
        while not stop.is_set():
            if store.collection_room(reservation):
                paused = False
                return True
            paused = True
            if not upload or reservation > store.policy.payload_bytes:
                return False
            work_ready.set()
            stop.wait(0.2)
        return False

    def commit(kind, payload):
        while not stop.is_set():
            try:
                store.enqueue(kind, payload)
                work_ready.set()
                return True
            except CapacityError:
                if not wait_for_room(len(payload), exact=True):
                    return False
        return False

    if upload:
        telemetry = telemetry_uploader or TelemetryUploader(store)
        diagnostic = diagnostic_uploader or DiagnosticUploader(store)
        def upload_loop():
            try:
                while not shutdown.is_set():
                    state = telemetry.cycle()
                    if state == "diagnostic_head":
                        state = diagnostic.cycle()
                    if state != "settled":
                        work_ready.wait(0.2)
                        work_ready.clear()
            except Exception as error:
                errors.append(error)
                stop.set()
        worker = threading.Thread(target=upload_loop, name="autopulse-upload", daemon=True)
        worker.start()

    try:
        if source is not None:
            source.connect()
            diagnostics_done = False
            try:
                while not stop.is_set() and readings < max_readings:
                    if not wait_for_room(MAX_PAYLOAD_BYTES):
                        break
                    try:
                        sample = source.read()
                    except StopIteration:
                        completed = True
                        break
                    if not commit("telemetry", json_codec.dumps(sample_to_payload(sample), separators=(",", ":")).encode("utf-8")):
                        break
                    readings += 1
                    if not diagnostics_done:
                        if not wait_for_room(65536):
                            break
                        try:
                            snapshot = source.read_diagnostics()
                        except StopIteration:
                            diagnostics_done = True
                        else:
                            if not commit("diagnostic", snapshot_to_json(snapshot).encode("utf-8")):
                                break
                            diagnostics += 1
                    if stop.wait(pace):
                        break
            except (OutboxError, OSError):
                try:
                    store.record_storage_failure()
                except OutboxError:
                    pass
                raise
            finally:
                source.close()
                store.stop_collection()
        if upload and not stop.is_set():
            deadline = time.monotonic() + drain_seconds
            while time.monotonic() < deadline and not stop.is_set():
                if not store.pending(limit=1) or store.has_blocked():
                    break
                stop.wait(min(0.2, max(0.0, deadline - time.monotonic())))
    finally:
        shutdown.set()
        work_ready.set()
        if worker is not None:
            worker.join(10)
            if worker.is_alive():
                raise WorkerStillRunning("upload worker did not stop; retain ownership until process exits")
    if errors:
        raise RuntimeError("upload worker failed") from errors[0]
    return {"readings": readings, "diagnostics": diagnostics, "scenario_completed": completed,
            "interrupted": stop.is_set(), "capacity_paused": paused, "status": store.status()}
