#!/usr/bin/env python3
"""Compact CMake/Make output while preserving a complete build log."""

from __future__ import annotations

import argparse
import re
import select
import sys
import time
from pathlib import Path, PurePosixPath

ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
BUILD_RE = re.compile(r"^\[\s*(\d+)%\]\s+Building\s+(CXX|C)\s+object\s+(.+)$")
LINK_RE = re.compile(r"^\[\s*(\d+)%\]\s+Linking\s+.+?\s+([^/\s]+)$")
BUILT_RE = re.compile(r"^\[\s*(\d+)%\]\s+Built target\s+(.+)$")
STEP_RE = re.compile(r"^\[\s*(\d+)%\]\s+Performing\s+(.+?)\s+step for '([^']+)'$")
WARNING_RE = re.compile(r"\bwarning:", re.IGNORECASE)
IMPORTANT_RE = re.compile(
    r"(?:\berror:|fatal error:|undefined reference|ld(?:\.lld)?: error|"
    r"clang\+\+: error|clang: error|collect2: error|ninja: build stopped|"
    r"make(?:\[\d+\])?: \*\*\*|CMake Error|FAILED:|"
    r"\bKilled\b|out of memory|std::bad_alloc|LLVM ERROR|signal 9)",
    re.IGNORECASE,
)


def compact_object_name(object_path: str) -> str:
    name = PurePosixPath(object_path.strip()).name
    if name.endswith(".o"):
        name = name[:-2]
    return name


def shorten_path(line: str) -> str:
    # GitHub-hosted runner paths are stable but excessively long. Keep only the
    # project-relative part and then collapse the ExternalProject source prefix.
    line = re.sub(r"/home/runner/work/[^/]+/[^/]+/", "", line)
    line = re.sub(
        r"buildscripts/build/[^/]+/arenamp-prefix/src/arenamp(?:-build)?/",
        "",
        line,
    )
    return line


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True, help="Path for the complete unfiltered log")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    warnings = 0
    last_status: tuple[str, str] | None = None

    with log_path.open("w", encoding="utf-8", errors="replace") as full_log:
        last_input = time.monotonic()
        while True:
            readable, _, _ = select.select([sys.stdin], [], [], 60.0)
            if not readable:
                silent_for = int(time.monotonic() - last_input)
                print(
                    f"==> Native compiler/linker is still running ({silent_for}s without output)...",
                    flush=True,
                )
                continue

            raw_line = sys.stdin.readline()
            if raw_line == "":
                break
            last_input = time.monotonic()
            full_log.write(raw_line)
            full_log.flush()

            line = ANSI_RE.sub("", raw_line.rstrip("\r\n"))

            match = BUILD_RE.match(line)
            if match:
                percent, language, object_path = match.groups()
                label = "C++" if language == "CXX" else "C"
                print(f"[{int(percent):3d}%] {label:<3} {compact_object_name(object_path)}", flush=True)
                continue

            match = LINK_RE.match(line)
            if match:
                percent, target = match.groups()
                print(f"[{int(percent):3d}%] LINK {target}", flush=True)
                continue

            match = BUILT_RE.match(line)
            if match:
                percent, target = match.groups()
                if target in ("openmw", "arenamw") or percent == "100":
                    print(f"[{int(percent):3d}%] DONE {target}", flush=True)
                continue

            match = STEP_RE.match(line)
            if match:
                percent, step, target = match.groups()
                status = (step, target)
                if status != last_status:
                    print(f"[{int(percent):3d}%] {target}: {step}", flush=True)
                    last_status = status
                continue

            if WARNING_RE.search(line):
                warnings += 1
                continue

            if IMPORTANT_RE.search(line):
                print(shorten_path(line), flush=True)

    if warnings:
        print(
            f"==> Hidden compiler warnings: {warnings}. Full output: {log_path}",
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
