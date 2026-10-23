from pathlib import Path

import pytest

from scripts import tokens


def _value(env: Path) -> dict[str, str]:
    line = next(
        ln for ln in env.read_text(encoding="utf-8").splitlines() if ln.startswith("DEVICE_TOKENS=")
    )
    pairs = [p.split(":", 1) for p in line.split("=", 1)[1].split(",") if p]
    return {device: token for device, token in pairs}


def test_add_creates_entry_with_43_char_token(tmp_path):
    env = tmp_path / ".env"
    token = tokens.add(env, "win")
    assert len(token) == 43
    assert env.read_text(encoding="utf-8").strip() == f"DEVICE_TOKENS=win:{token}"


def test_add_keeps_existing_devices_and_other_lines(tmp_path):
    env = tmp_path / ".env"
    env.write_text("FOO=bar\n", encoding="utf-8")
    t_win = tokens.add(env, "win")
    t_android = tokens.add(env, "android")
    assert _value(env) == {"win": t_win, "android": t_android}
    assert "FOO=bar" in env.read_text(encoding="utf-8")


def test_add_existing_device_rotates_token(tmp_path):
    env = tmp_path / ".env"
    old = tokens.add(env, "win")
    new = tokens.add(env, "win")
    assert old != new
    assert _value(env) == {"win": new}


def test_revoke_leaves_only_other_device(tmp_path):
    env = tmp_path / ".env"
    tokens.add(env, "win")
    t_android = tokens.add(env, "android")
    assert tokens.revoke(env, "win") is True
    assert _value(env) == {"android": t_android}


def test_revoke_unknown_returns_false(tmp_path):
    env = tmp_path / ".env"
    tokens.add(env, "win")
    assert tokens.revoke(env, "nope") is False


def test_rejects_bad_device_name(tmp_path):
    with pytest.raises(ValueError):
        tokens.add(tmp_path / ".env", "bad:name")
