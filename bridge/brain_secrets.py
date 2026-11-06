#!/usr/bin/env python3
"""Uploads sebastian-brain's secrets from .env to Cloudflare (nothing is printed).

    python bridge/brain_secrets.py

Run it after `npm run deploy` in brain/. See .env.example for the names; anything left blank is skipped.
"""
import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAMES = ("ASSEMBLYAI_API_KEY", "SEBASTIAN_TOKEN", "GEMINI_API_KEY", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
         "GOOGLE_REFRESH_TOKEN", "NOTION_TOKEN", "NOTION_PARENT_PAGE_ID", "OWNER_WA", "CALENDLY_URL")


def read_env(path: Path) -> dict:
    out = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def main() -> None:
    env = read_env(ROOT / ".env")
    secrets = {name: env[name] for name in NAMES if env.get(name)}
    fd, path = tempfile.mkstemp(suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(secrets, f)
        subprocess.run("npx wrangler secret bulk " + f'"{path}"', cwd=ROOT / "brain", shell=True, check=True)
    finally:
        os.unlink(path)
    print("Uploaded:", ", ".join(secrets))


if __name__ == "__main__":
    main()
