"""Latency benchmark: end-of-upload to transcript / first reply_text / first audio byte.

Usage: python -m scripts.bench --url wss://.../ws --token T samples/*.wav
Exits 1 if the median time to first audio exceeds 2.0 s.
"""

import argparse
import asyncio
import json
import statistics
import sys
import time
import wave

import websockets

IN_RATE = 16000
FRAME_MS = 20
LIMIT_S = 2.0


async def measure(url: str, token: str, path: str) -> dict[str, float | None]:
    with wave.open(path, "rb") as w:
        if w.getframerate() != IN_RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise SystemExit(f"{path}: must be 16 kHz mono PCM16 wav")
        pcm = w.readframes(w.getnframes())
    frame_bytes = IN_RATE * FRAME_MS // 1000 * 2
    res: dict[str, float | None] = {"transcript": None, "reply": None, "audio": None}

    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps({"type": "hello", "token": token, "device": "bench"}))
        for i in range(0, len(pcm), frame_bytes):
            await ws.send(pcm[i : i + frame_bytes])
            await asyncio.sleep(FRAME_MS / 1000)
        t0 = time.perf_counter()
        await ws.send(json.dumps({"type": "end_utterance"}))
        async for msg in ws:
            now = time.perf_counter() - t0
            if isinstance(msg, bytes):
                res["audio"] = now
                break
            data = json.loads(msg)
            kind = data.get("type")
            if kind == "transcript" and data.get("final") and res["transcript"] is None:
                res["transcript"] = now
            elif kind == "reply_text" and res["reply"] is None:
                res["reply"] = now
            elif kind == "error":
                print(f"{path}: server error {data.get('code')}: {data.get('message')}")
                break
    return res


def _fmt(v: float | None) -> str:
    return "n/a" if v is None else f"{v:.2f}"


async def run(url: str, token: str, files: list[str]) -> int:
    rows = []
    print(f"{'sample':40} {'transcript':>10} {'reply':>8} {'audio':>8}")
    for path in files:
        r = await measure(url, token, path)
        rows.append(r)
        print(f"{path[-40:]:40} {_fmt(r['transcript']):>10} {_fmt(r['reply']):>8} {_fmt(r['audio']):>8}")
    audio = [r["audio"] for r in rows if r["audio"] is not None]
    if not audio:
        print("no audio received for any sample")
        return 1
    med = statistics.median(audio)
    print(f"median to first audio: {med:.2f} s (limit {LIMIT_S:.1f} s)")
    return 0 if med <= LIMIT_S and len(audio) == len(rows) else 1


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--url", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("files", nargs="+")
    a = p.parse_args()
    sys.exit(asyncio.run(run(a.url, a.token, a.files)))


if __name__ == "__main__":
    main()
