#!/usr/bin/env python3
"""Pulls a recorded call off the phone and mixes it into one WAV: the other person + Sebastian (ear) and your mic.

    python bridge/export_call.py            # newest call with audio
    python bridge/export_call.py <stamp>    # a specific call, e.g. 1793972266221

Needs ffmpeg on PATH and "record_call": true in sebastian.json (already on). Output: calls/call_<stamp>.wav
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REMOTE = "/data/local/tmp/sebastian-rec"
ADB = os.environ.get("ADB", "adb")


def adb(*args: str) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def main() -> None:
    listing = adb("shell", f"ls -l {REMOTE}")
    sizes = {m.group(2): int(m.group(1)) for m in re.finditer(r"(\d+)\s+\S+\s+\S+\s+(call_\d+_ear\.pcm)", listing)}
    stamps = sorted(k[5:-8] for k, v in sizes.items() if v > 160_000)
    if not stamps:
        sys.exit("no recorded call with audio on the phone")
    stamp = sys.argv[1] if len(sys.argv) > 1 else stamps[-1]
    out_dir = ROOT / "calls"
    out_dir.mkdir(exist_ok=True)
    files = {}
    for part in ("ear", "mic"):
        local = out_dir / f"call_{stamp}_{part}.pcm"
        adb("pull", f"{REMOTE}/call_{stamp}_{part}.pcm", str(local))
        files[part] = local
    wav = out_dir / f"call_{stamp}.wav"
    pcm = ["-f", "s16le", "-ar", "16000", "-ac", "1"]
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *pcm, "-i", str(files["ear"]), *pcm, "-i", str(files["mic"]),
                    "-filter_complex", "amix=inputs=2:duration=longest:normalize=0", str(wav)], check=True)
    for f in files.values():
        f.unlink()
    print(wav)


if __name__ == "__main__":
    main()
