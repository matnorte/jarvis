"""Stream a 16 kHz mono wav to the gateway and save the spoken reply as a 24 kHz wav.

Usage: python -m scripts.talk --url wss://jarvis.openclawchip.tech/ws --token T in.wav out.wav
"""

import argparse
import asyncio
import json
import wave

import websockets

IN_RATE = 16000
OUT_RATE = 24000
FRAME_MS = 20


async def talk(url: str, token: str, device: str, in_path: str, out_path: str) -> None:
    with wave.open(in_path, "rb") as w:
        if w.getframerate() != IN_RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise SystemExit("input must be 16 kHz mono PCM16 wav")
        pcm = w.readframes(w.getnframes())
    frame_bytes = IN_RATE * FRAME_MS // 1000 * 2
    audio = bytearray()

    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps({"type": "hello", "token": token, "device": device}))

        async def send_audio() -> None:
            for i in range(0, len(pcm), frame_bytes):
                await ws.send(pcm[i : i + frame_bytes])
                await asyncio.sleep(FRAME_MS / 1000)
            await ws.send(json.dumps({"type": "end_utterance"}))

        sender = asyncio.create_task(send_audio())
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    audio += msg
                    continue
                data = json.loads(msg)
                kind = data.get("type")
                if kind == "transcript" and data.get("final"):
                    print(f"[you:{data.get('lang')}] {data['text']}")
                elif kind == "reply_text":
                    print(data["delta"], end="", flush=True)
                elif kind == "error":
                    print(f"\n[error {data.get('code')}] {data.get('message')}")
                elif kind == "speaking_done":
                    print()
                    break
        finally:
            sender.cancel()

    with wave.open(out_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(OUT_RATE)
        w.writeframes(bytes(audio))
    print(f"wrote {len(audio)} bytes of audio to {out_path}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--url", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--device", default="talk-script")
    p.add_argument("input")
    p.add_argument("output")
    a = p.parse_args()
    asyncio.run(talk(a.url, a.token, a.device, a.input, a.output))


if __name__ == "__main__":
    main()
