#!/usr/bin/env python3
"""Finds where a WhatsApp call's echo comes from, using the recordings Sebastian saves with "record_call": true.

    python bridge/echo_check.py              # pull the newest call from the phone and analyse it
    python bridge/echo_check.py <dir> <name> # analyse files already on this PC (name like call_1730...)

Question it answers: is the other person's voice inside what Sebastian sends back to them ("out")?
It compares the other side's voice as it arrived ("in", and "ear" = what played on the earpiece) with Rayyan's raw
mic and the outgoing stream, finds the delay where they match best, and how strong the copy is.
Also writes .wav copies so the streams can be listened to.
"""
import os
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

ADB = os.environ.get("ADB", "adb")
REMOTE = "/data/local/tmp/sebastian-rec"
RATE = 16000
LOCAL = Path(__file__).resolve().parent.parent / "recordings"


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=True).stdout


def pull_newest() -> str:
    names = sorted(n for n in adb("shell", f"ls {REMOTE}").split() if n.endswith(".txt"))
    if not names:
        sys.exit("No recordings on the phone yet")
    name = names[-1][:-4]
    LOCAL.mkdir(exist_ok=True)
    for suffix in ("_in.pcm", "_ear.pcm", "_mic.pcm", "_out.pcm", ".txt"):
        adb("pull", f"{REMOTE}/{name}{suffix}", str(LOCAL / f"{name}{suffix}"))
    return name


def load(d: Path, name: str):
    meta = {}
    for line in (d / f"{name}.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) == 3:
            meta[parts[0]] = int(parts[1].split("=")[1])
    streams = {k: np.fromfile(d / f"{name}_{k}.pcm", dtype="<i2").astype(np.float32) for k in ("in", "ear", "mic", "out")}
    return streams, meta


def envelope(x: np.ndarray) -> np.ndarray:
    """Loudness per 10 ms (160 samples), in dB, so voice shapes can be lined up without caring about phase."""
    n = len(x) // 160
    rms = np.sqrt(np.mean(x[: n * 160].reshape(n, 160) ** 2, axis=1) + 1.0)
    return 20 * np.log10(rms)


def best_lag(ref: np.ndarray, sig: np.ndarray, max_ms=1500):
    """Lag (ms, sig after ref) where sig's loudness pattern matches ref's best, and the match score (0-1)."""
    a = ref - ref.mean(); b = sig - sig.mean()
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    best = (0, -1.0)
    scores = []
    for lag in range(-50, max_ms // 10 + 1):
        if lag >= 0:
            x, y = a[: n - lag], b[lag:]
        else:
            x, y = a[-lag:], b[: n + lag]
        if len(x) < 300:
            continue
        c = float(np.dot(x, y) / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-9))
        scores.append(c)
        if c > best[1]:
            best = (lag * 10, c)
    # How far the peak stands out from all other delays: a real copy gives a sharp peak (above ~3.5).
    sharp = (best[1] - float(np.median(scores))) / (float(np.std(scores)) + 1e-9) if scores else 0.0
    return best[0], best[1], sharp


def aligned(meta, ref_key, sig_key, streams):
    """Trims both streams to the same phone-clock start, so a lag of 0 means 'at the same moment'."""
    ref, sig = streams[ref_key], streams[sig_key]
    off = int(round((meta[sig_key] - meta[ref_key]) / 1e9 * RATE))  # sig started this many samples after ref
    if off > 0:
        ref = ref[off:]
    elif off < 0:
        sig = sig[-off:]
    return ref, sig


def far_only_level(ref, sig, lag_ms):
    """How loud the copy is: sig level while only the far side talks (ref loud), vs ref's level, in dB."""
    lag = lag_ms * RATE // 1000
    if lag > 0:
        ref, sig = ref[: len(ref) - lag], sig[lag:]
    n = min(len(ref), len(sig)) // 160
    er = envelope(ref[: n * 160]); es = envelope(sig[: n * 160])
    loud = er > np.percentile(er, 80)
    return float(np.median(es[loud]) - np.median(er[loud])), float(np.median(es[~loud]))


def wav(d: Path, name: str, key: str, x: np.ndarray):
    with wave.open(str(d / f"{name}_{key}.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(np.clip(x, -32768, 32767).astype("<i2").tobytes())


def main():
    if len(sys.argv) == 3:
        d, name = Path(sys.argv[1]), sys.argv[2]
    else:
        d, name = LOCAL, pull_newest()
    streams, meta = load(d, name)
    for k, x in streams.items():
        wav(d, name, k, x)
    secs = {k: len(x) / RATE for k, x in streams.items()}
    print(f"Call {name}: " + ", ".join(f"{k} {s:.0f}s" for k, s in secs.items()))
    print(f"WAV copies in {d}\n")
    for ref_key, sig_key in (("ear", "mic"), ("in", "mic"), ("in", "out")):
        ref, sig = aligned(meta, ref_key, sig_key, streams)
        lag, score, sharp = best_lag(envelope(ref), envelope(sig))
        rel, quiet = far_only_level(ref, sig, max(lag, 0))
        found = "THEIR VOICE IS IN HERE" if sharp > 3.5 and 0 < lag <= 600 else "no clear copy"
        print(f"{ref_key:>3} -> {sig_key:<3}: {found:<22} (peak {score:.2f} at {lag:+d} ms, stands out x{sharp:.1f}; level {rel:+.1f} dB)")
    print("\nHow to read it: a match above ~0.5 at a small positive delay (20-200 ms) means the other side's voice is")
    print("inside that stream. ear->mic high = the earpiece leaks into the mic. in->out high = Sebastian sends their")
    print("voice back. All low = what Sebastian sends doesn't contain their voice; the echo is made elsewhere.")


if __name__ == "__main__":
    main()
