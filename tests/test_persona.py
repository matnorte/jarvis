from pathlib import Path

PERSONA = Path(__file__).resolve().parent.parent / "persona"


def test_deep_soul_is_fast_soul_without_routing():
    fast = (PERSONA / "SOUL.md").read_text(encoding="utf-8")
    deep = (PERSONA / "SOUL.deep.md").read_text(encoding="utf-8")
    assert "[[DEEP]]" in fast and "ROUTING" in fast
    assert "[[DEEP]]" not in deep and "ROUTING" not in deep
    assert "Give the thorough answer, but keep the spoken part concise and speakable." in deep
    for section in ("CHARACTER", "SPEECH RULES", "LANGUAGE", "BOUNDARIES"):
        assert section in deep
    fast_lines = {ln for ln in fast.splitlines() if ln.strip()}
    deep_lines = {ln for ln in deep.splitlines() if ln.strip()}
    extra = deep_lines - fast_lines
    assert extra == {"- Give the thorough answer, but keep the spoken part concise and speakable."}
