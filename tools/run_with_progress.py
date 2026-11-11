"""Run a command, forwarding output and reporting elapsed time during quiet periods."""

from __future__ import annotations

import argparse
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import TextIO


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True, help="work being performed")
    parser.add_argument("--interval", type=int, default=60, help="progress interval in seconds")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required after --")
    if args.interval < 1:
        parser.error("--interval must be at least one second")

    print(f"Starting {args.label}.", flush=True)
    process = subprocess.Popen(
        command,
        cwd=Path(__file__).resolve().parents[1] / "src",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output: queue.Queue[tuple[str | None, str | None]] = queue.Queue()

    def forward(stream: str, lines: TextIO) -> None:
        for line in lines:
            output.put((stream, line))
        output.put((stream, None))

    threading.Thread(target=forward, args=("stdout", process.stdout), daemon=True).start()
    threading.Thread(target=forward, args=("stderr", process.stderr), daemon=True).start()

    started = time.monotonic()
    next_progress = started + args.interval
    streams_open = 2
    while process.poll() is None or streams_open:
        wait = max(0.0, next_progress - time.monotonic())
        try:
            stream, line = output.get(timeout=wait)
        except queue.Empty:
            elapsed = int(time.monotonic() - started)
            print(f"Still {args.label} ({elapsed} seconds elapsed).", flush=True)
            next_progress += args.interval
            continue

        if line is None:
            streams_open -= 1
        else:
            destination = sys.stdout if stream == "stdout" else sys.stderr
            destination.write(line)
            destination.flush()

    return process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
