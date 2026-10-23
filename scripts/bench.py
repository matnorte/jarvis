"""Latency benchmark per sample, measured from end of upload (end_utterance sent):
transcript, first reply_text, first audio (any, incl. canned filler) and first reply audio
(first audio after the first reply_text). The 2.0 s gate applies to the median first reply audio.

Usage: python -m scripts.bench --url wss://.../ws --token T [--timeout 30] samples/*.wav
Exits 1 if the median first reply audio exceeds 2.0 s or any sample fails or times out.
"""

import argparse
import asyncio
import json
import statistics
import sys
import time
import wave

import websockets
from websockets.exceptions import ConnectionClosed, InvalidHandshake

IN_RATE = 16000
FRAME_MS = 20
LIMIT_S = 2.0


async def _measure(url: str, token: str, pcm: bytes, res: dict) -> None:
    frame_bytes = IN_RATE * FRAME_MS // 1000 * 2
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
                if res["audio"] is None:
                    res["audio"] = now
                if res["reply"] is not None and res["reply_audio"] is None:
                    res["reply_audio"] = now
                    return
                continue
            data = json.loads(msg)
            kind = data.get("type")
            if kind == "transcript" and data.get("final") and res["transcript"] is None:
                res["transcript"] = now
            elif kind == "reply_text" and res["reply"] is None:
                res["reply"] = now
            elif kind == "error":
                res["status"] = f"error {data.get('code')}"
            elif kind == "speaking_done" or (kind == "state" and data.get("state") == "idle"):
                if res["reply_audio"] is None and res["status"] == "ok":
                    res["status"] = "no reply audio"
                return


async def measure(url: str, token: str, path: str, timeout: float) -> dict:
    res: dict = {
        "transcript": None, "reply": None, "audio": None, "reply_audio": None, "status": "ok",
    }
    with wave.open(path, "rb") as w:
        if w.getframerate() != IN_RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
            res["status"] = "bad wav (need 16 kHz mono PCM16)"
            return res
        pcm = w.readframes(w.getnframes())
    try:
        await asyncio.wait_for(_measure(url, token, pcm, res), timeout)
    except asyncio.TimeoutError:
        res["status"] = "timeout"
    except ConnectionClosed as exc:
        res["status"] = f"closed {exc.rcvd.code if exc.rcvd else '?'}"
    except (InvalidHandshake, OSError) as exc:
        res["status"] = f"connect failed: {exc}"
    if res["status"] == "ok" and res["reply_audio"] is None:
        res["status"] = "no reply audio"
    return res


def _fmt(v: float | None) -> str:
    return "n/a" if v is None else f"{v:.2f}"


async def run(url: str, token: str, files: list[str], timeout: float) -> int:
    rows = []
    print(f"{'sample':32} {'transcript':>10} {'reply':>7} {'audio':>7} {'reply_audio':>11}  status")
    for path in files:
        r = await measure(url, token, path, timeout)
        rows.append(r)
        print(
            f"{path[-32:]:32} {_fmt(r['transcript']):>10} {_fmt(r['reply']):>7} "
            f"{_fmt(r['audio']):>7} {_fmt(r['reply_audio']):>11}  {r['status']}"
        )
    failed = sum(1 for r in rows if r["status"] != "ok")
    ok = [r["reply_audio"] for r in rows if r["reply_audio"] is not None]
    if not ok:
        print("no reply audio received for any sample")
        return 1
    med = statistics.median(ok)
    any_med = statistics.median([r["audio"] for r in rows if r["audio"] is not None])
    print(f"median first audio (any): {any_med:.2f} s")
    print(f"median first reply audio: {med:.2f} s (limit {LIMIT_S:.1f} s); failed samples: {failed}")
    return 0 if med <= LIMIT_S and failed == 0 else 1


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--url", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--timeout", type=float, default=30.0, help="per-sample seconds")
    p.add_argument("files", nargs="+")
    a = p.parse_args()
    sys.exit(asyncio.run(run(a.url, a.token, a.files, a.timeout)))


if __name__ == "__main__":
    main()
