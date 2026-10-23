"""Manage DEVICE_TOKENS in a .env file.

Usage:
    python -m scripts.tokens add <device> [--env deploy/.env]
    python -m scripts.tokens revoke <device> [--env deploy/.env]

The file is written with mode 600. The gateway reads it only when its container
is created, so apply any change (add or revoke) with
    docker compose -f deploy/docker-compose.yml up -d
`docker compose restart` does NOT reload env_file, so a revoked token would
keep working until the container is recreated.
"""

import argparse
import os
import re
import secrets
import sys
from pathlib import Path

KEY = "DEVICE_TOKENS"
_DEVICE_RE = re.compile(r"^[A-Za-z0-9_.-]+$")


def _read(env: Path) -> tuple[list[str], dict[str, str]]:
    """Return (other lines, device -> token) from the env file."""
    others: list[str] = []
    devices: dict[str, str] = {}
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith(f"{KEY}="):
                for pair in line.split("=", 1)[1].split(","):
                    device, sep, token = pair.strip().partition(":")
                    if sep and device and token:
                        devices[device] = token
            else:
                others.append(line)
    return others, devices


def _write(env: Path, others: list[str], devices: dict[str, str]) -> None:
    value = ",".join(f"{d}:{t}" for d, t in devices.items())
    lines = [*others, f"{KEY}={value}"]
    data = ("\n".join(lines) + "\n").encode("utf-8")
    # Create owner-only; the mode applies only on creation, so also tighten an
    # existing file. On Windows chmod only toggles read-only (best effort).
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_BINARY", 0)
    fd = os.open(env, flags, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.chmod(env, 0o600)


def add(env: Path, device: str) -> str:
    if not _DEVICE_RE.match(device):
        raise ValueError(f"invalid device name: {device!r}")
    others, devices = _read(env)
    token = secrets.token_urlsafe(32)
    devices[device] = token
    _write(env, others, devices)
    return token


def revoke(env: Path, device: str) -> bool:
    others, devices = _read(env)
    if device not in devices:
        return False
    del devices[device]
    _write(env, others, devices)
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Manage device tokens in .env",
        epilog="Then run `docker compose -f deploy/docker-compose.yml up -d` "
        "(not restart) so the gateway reloads the env file.",
    )
    parser.add_argument("action", choices=["add", "revoke"])
    parser.add_argument("device")
    parser.add_argument("--env", type=Path, default=Path("deploy/.env"))
    args = parser.parse_args(argv)
    try:
        if args.action == "add":
            print(add(args.env, args.device))
        elif not revoke(args.env, args.device):
            print(f"no such device: {args.device}", file=sys.stderr)
            return 1
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
