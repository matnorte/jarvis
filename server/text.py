"""Speech text helpers: markdown cleanup and streaming clause chunking."""

import re
from urllib.parse import urlparse

_FENCE = re.compile(r"^\s*```.*$", re.MULTILINE)
_LINK = re.compile(r"\[([^\]]*)\]\((?:[^)\s]*)\)")
_URL = re.compile(r"https?://[^\s<>()\[\]]+")
_URL_TRAIL = ".,;:!?'\""
_INLINE_CODE = re.compile(r"`([^`]*)`")
_HEADING = re.compile(r"^\s{0,3}#{1,6}\s+", re.MULTILINE)
_BULLET = re.compile(r"^\s*[-*+]\s+", re.MULTILINE)
_BOLD = re.compile(r"(\*\*|__)(?=\S)(.+?)(?<=\S)\1", re.DOTALL)
_ITALIC_STAR = re.compile(r"\*(?=\S)([^*]+?)(?<=\S)\*")
_ITALIC_UNDER = re.compile(r"(?<![\w])_(?=\S)([^_]+?)(?<=\S)_(?![\w])")
_SPACES = re.compile(r"\s+")


def _url_to_domain(match: re.Match[str]) -> str:
    url = match.group(0)
    trail = ""
    while url and url[-1] in _URL_TRAIL:
        trail = url[-1] + trail
        url = url[:-1]
    host = urlparse(url).hostname or url
    if host.startswith("www."):
        host = host[4:]
    return host + trail


def clean_for_speech(text: str) -> str:
    text = _FENCE.sub("", text)
    text = _LINK.sub(r"\1", text)
    text = _URL.sub(_url_to_domain, text)
    text = _INLINE_CODE.sub(r"\1", text)
    text = _HEADING.sub("", text)
    text = _BULLET.sub("", text)
    text = _BOLD.sub(r"\2", text)
    text = _ITALIC_STAR.sub(r"\1", text)
    text = _ITALIC_UNDER.sub(r"\1", text)
    return _SPACES.sub(" ", text).strip()


_STAR_BULLET = re.compile(r"^[ \t]*\*[ \t]", re.MULTILINE)
_LEFTOVER = re.compile(r"[*`\[\]]")


def _unclosed(prefix: str) -> bool:
    """True if prefix ends inside inline code, emphasis or a link label."""
    if prefix.count("`") % 2:
        return True
    if _STAR_BULLET.sub("", prefix).count("*") % 2:
        return True
    return prefix.count("[") > prefix.count("]")


# Whole sentences keep ElevenLabs' intonation natural. Clause breaks are a
# latency valve: the first chunk may end at a clause once it has 40 characters
# (so the first audio is not held up by a long opening sentence); later chunks
# only once they reach 100 characters.
_FIRST_CLAUSE_CHARS = 40
_LONG_CLAUSE_CHARS = 100
_CLAUSE_BREAK = re.compile(r"[,;:](?=\s)")
_SENTENCE_BREAK = re.compile(r"[.!?](?=\s)")


class ClauseChunker:
    """Splits streamed text into speakable chunks.

    Chunks end at sentence ends (. ! ?), or at , ; : once a chunk holds at least
    40 characters (first chunk) or 100 characters (later chunks). A boundary
    needs whitespace after the punctuation, so trailing punctuation waits for
    more text (e.g. "3.5", "U.S."). flush() emits whatever remains.
    """

    def __init__(self) -> None:
        self._buf = ""
        self._first_done = False

    def _emit(self, raw: str, out: list[str]) -> None:
        # Delimiters left over (flush with an unclosed one) must never be spoken.
        cleaned = clean_for_speech(_LEFTOVER.sub("", clean_for_speech(raw)))
        if cleaned:
            out.append(cleaned)

    def feed(self, delta: str) -> list[str]:
        self._buf += delta
        out: list[str] = []
        while True:
            end = self._find_boundary()
            if end is None:
                break
            self._emit(self._buf[:end], out)
            self._buf = self._buf[end:]
            self._first_done = True
        return out

    def _find_boundary(self) -> int | None:
        clause_min = _LONG_CLAUSE_CHARS if self._first_done else _FIRST_CLAUSE_CHARS
        candidates = [m.end() for m in _SENTENCE_BREAK.finditer(self._buf)]
        candidates += [
            m.end()
            for m in _CLAUSE_BREAK.finditer(self._buf)
            if len(self._buf[: m.end()].strip()) >= clause_min
        ]
        for end in sorted(candidates):
            if not _unclosed(self._buf[:end]):
                return end
        return None

    def flush(self) -> list[str]:
        out: list[str] = []
        self._emit(self._buf, out)
        self._buf = ""
        return out
