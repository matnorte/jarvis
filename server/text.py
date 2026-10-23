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


_MIN_FIRST_CHARS = 12
_ANY_BREAK = re.compile(r"[,;:.!?](?=\s)")
_SENTENCE_BREAK = re.compile(r"[.!?](?=\s)")


class ClauseChunker:
    """Splits streamed text into speakable chunks.

    The first chunk may end at any of , ; : . ! ? once it holds at least 12
    characters; later chunks end only at sentence ends. A boundary needs
    whitespace after the punctuation, so trailing punctuation waits for more
    text (e.g. "3.5", "U.S."). flush() emits whatever remains.
    """

    def __init__(self) -> None:
        self._buf = ""
        self._first_done = False

    def _emit(self, raw: str, out: list[str]) -> None:
        cleaned = clean_for_speech(raw)
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
        if self._first_done:
            m = _SENTENCE_BREAK.search(self._buf)
            return m.end() if m else None
        for m in _ANY_BREAK.finditer(self._buf):
            if len(self._buf[: m.end()].strip()) >= _MIN_FIRST_CHARS:
                return m.end()
        return None

    def flush(self) -> list[str]:
        out: list[str] = []
        self._emit(self._buf, out)
        self._buf = ""
        return out
