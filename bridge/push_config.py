#!/usr/bin/env python3
"""Builds Sebastian's phone config from .env + contacts.json + sebastian.json and pushes it to the phone.

    python bridge/push_config.py            # push to the only adb device (or set ANDROID_SERIAL)
    python bridge/push_config.py --print    # show what would be pushed, with the secrets masked

The file lands in /data/local/tmp/sebastian/config.json, which only adb's shell user (Sebastian's helper) can read.
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = os.environ.get("ADB", "adb")
REMOTE_DIR = "/data/local/tmp/sebastian"


def read_env(path: Path) -> dict:
    env = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def build() -> dict:
    env = read_env(ROOT / ".env")
    key = env.get("ASSEMBLYAI_API_KEY", "")
    if not key:
        sys.exit("ASSEMBLYAI_API_KEY is missing from .env")
    cfg = json.loads((ROOT / "sebastian.json").read_text(encoding="utf-8"))
    contacts = ROOT / "contacts.json"
    cfg["contacts"] = json.loads(contacts.read_text(encoding="utf-8")) if contacts.exists() else []
    cfg["assemblyai_api_key"] = key
    cfg["brain_url"] = env.get("SEBASTIAN_BRAIN_URL", "")
    cfg["brain_token"] = env.get("SEBASTIAN_TOKEN", "")
    for server in cfg.get("mcp_servers", []):
        server["token"] = env.get(server.pop("token_env", ""), "")
    return cfg


def masked(cfg: dict) -> dict:
    out = dict(cfg)
    for k in ("assemblyai_api_key", "brain_token"):
        if out.get(k):
            out[k] = out[k][:4] + "…"
    out["mcp_servers"] = [{**s, "token": "…" if s.get("token") else ""} for s in cfg.get("mcp_servers", [])]
    return out


def adb(*args: str) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def main() -> None:
    cfg = build()
    if "--print" in sys.argv:
        print(json.dumps(masked(cfg), indent=2, ensure_ascii=False))
        return
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False)
        local = f.name
    try:
        adb("shell", f"mkdir -p {REMOTE_DIR} && chmod 700 {REMOTE_DIR}")
        adb("push", local, f"{REMOTE_DIR}/config.json")
        adb("shell", f"chmod 600 {REMOTE_DIR}/config.json")
    finally:
        os.unlink(local)
    print(f"Pushed: {len(cfg['contacts'])} contacts, voice {cfg.get('voice_id')}, brain {'set' if cfg['brain_url'] else 'NOT SET'}")


if __name__ == "__main__":
    main()
