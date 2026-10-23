"""Manage DEVICE_TOKENS in a .env file.

Usage:
    python -m scripts.tokens add <device> [--env deploy/.env]
    python -m scripts.tokens revoke <device> [--env deploy/.env]
"""

import argparse
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
    env.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


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
    parser = argparse.ArgumentParser(description="Manage device tokens in .env")
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
