"""Print a selected finite scenario as production-shaped JSON lines."""

import argparse
from . import json_codec as json

from .models.telemetry_json import sample_to_payload
from .scenario_args import add_scenario_arguments, source_from_args


def main() -> None:
    parser = argparse.ArgumentParser(description="Offline finite synthetic telemetry preview (no pacing)")
    add_scenario_arguments(parser)
    try:
        source = source_from_args(parser.parse_args())
    except (ValueError, OverflowError) as error:
        parser.error(str(error))
    source.connect()
    try:
        while True:
            try:
                sample = source.read()
            except StopIteration:
                break
            print(json.dumps(sample_to_payload(sample)))
    finally:
        source.close()


if __name__ == "__main__":
    main()
