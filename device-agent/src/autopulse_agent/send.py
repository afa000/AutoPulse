"""Explicit network mode; the separate simulate module remains offline."""

import argparse
import math
import os
import time
from dataclasses import asdict
from pathlib import Path

from . import json_codec as json

from .models.telemetry_json import sample_to_payload
from .scenario_args import add_scenario_arguments, source_from_args
from .capture import FORMAT, bounded_batches, capture_batches, encode_batch
from .transport.http import BatchSender, DeliveryError, DeliverySummary, HttpBatchClient


def send_source(source, sender, *, interval=2.0, batch_size=1, clock=time.monotonic, sleep=time.sleep, capture=None):
    if not math.isfinite(interval) or interval <= 0 or not 1 <= batch_size <= 100:
        raise ValueError("interval must be positive and finite; batch_size must be 1-100")
    total = DeliverySummary()
    def paced_readings():
        due = clock()
        while True:
            delay = max(0.0, due - clock())
            if delay:
                sleep(delay)
            try:
                sample = source.read()
            except StopIteration:
                return
            yield sample_to_payload(sample)
            due = clock() + interval

    source.connect()
    try:
        for batch in bounded_batches(paced_readings(), batch_size):
            if capture is not None:
                capture.write(encode_batch(batch) + b"\n")
                capture.flush()
            total += sender.deliver(batch)
    finally:
        source.close()
    return total


def replay_capture(stream, sender):
    total = DeliverySummary()
    for batch in capture_batches(stream):
        total += sender.deliver(batch)
    return total


def main():
    parser = argparse.ArgumentParser(description="Send a finite synthetic scenario or replay bounded capture batches")
    parser.add_argument("--server-url", default=os.getenv("AUTOPULSE_SERVER_URL", "http://127.0.0.1:8080"))
    add_scenario_arguments(parser)
    parser.add_argument("--interval", type=float, default=2.0, help="Wall-clock seconds between readings (separate from sample time)")
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--max-attempts", type=int, default=3)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--capture", type=Path, help="Create a new streaming capture; refuses to overwrite existing files")
    mode.add_argument("--replay", type=Path, help="Replay v1 batches or a legacy envelope with original content")
    args = parser.parse_args()
    try:
        sender = BatchSender(HttpBatchClient(args.server_url), max_attempts=args.max_attempts)
        if args.replay:
            with args.replay.open("rb") as stream:
                summary = replay_capture(stream, sender)
        else:
            if not math.isfinite(args.interval) or args.interval <= 0 or not 1 <= args.batch_size <= 100:
                raise ValueError("interval must be positive and finite; batch_size must be 1-100")
            source = source_from_args(args)
            if args.capture:
                with args.capture.open("xb") as capture:
                    capture.write(json.dumps({"format": FORMAT}).encode() + b"\n")
                    summary = send_source(source, sender, interval=args.interval, batch_size=args.batch_size, capture=capture)
            else:
                summary = send_source(source, sender, interval=args.interval, batch_size=args.batch_size)
        print(json.dumps(asdict(summary)))
        if summary.rejected:
            raise SystemExit(2)
    except (DeliveryError, ValueError, OSError, OverflowError) as error:
        parser.exit(1, f"Delivery failed: {error}\n")


if __name__ == "__main__":
    main()
