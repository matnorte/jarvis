# Jarvis Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A voice gateway on the VPS that turns streamed microphone audio into a spoken JARVIS reply. It uses faster-whisper, Hermes Agent (Haiku 5.5, escalating to Opus 5.5) and ElevenLabs, deployed at `jarvis.openclawchip.tech`.

**Architecture:** A FastAPI WebSocket server runs one `Session` per connection. Each turn goes STT, then the router, then TTS, and every stage sits behind a small protocol so tests use fakes. Hermes runs as two localhost profiles reached through its OpenAI-compatible API. Caddy terminates TLS in front of a Docker container.

**Tech Stack:** Python 3.12, FastAPI + uvicorn, faster-whisper, httpx, websockets, numpy, pytest + pytest-asyncio, uv, Docker Compose, Caddy, systemd.

**Spec:** `docs/superpowers/specs/2026-10-23-jarvis-backend-design.md`

## Global Constraints

- Python **3.12** (the local machine has 3.15; use `uv python install 3.12`, because faster-whisper/ctranslate2 wheels lag behind new Pythons).
- Audio in: PCM16 LE, mono, **16 kHz**. Audio out: PCM16 LE, mono, **24 kHz** (ElevenLabs `output_format=pcm_24000`).
- STT: faster-whisper model **`base`**, `compute_type="int8"`, `device="cpu"`, `beam_size=1`, language auto-detected (pt or en), `vad_filter=True`.
- LLM: fast profile **`claude-haiku-5-5`** on `127.0.0.1:8642`; deep profile **`claude-opus-5-5`** on `127.0.0.1:8643`; Hermes request `model` field `"hermes-agent"`; bearer `API_SERVER_KEY`.
- TTS: ElevenLabs **`eleven_flash_v2_5`**, WebSocket stream-input API, `language_code` `"pt"` or `"en"`, one voice id from config.
- Escalation marker: exactly **`[[DEEP]]`**, never spoken.
- Language tag on every user message: **`[lang:pt] `** or **`[lang:en] `** prefix.
- Timings: Hermes timeout **60 s**; "one moment" filler after **1.5 s** with no reply text; no-speech timeout **6 s**; server fallback endpointing after **700 ms** of silence.
- Canned lines (exact copy, pre-generated at startup into `cache/canned/{key}_{lang}.pcm`):
  - `ack_deep`: en "Let me think about that properly, sir." / pt "Deixe-me pensar nisso com calma, senhor."
  - `one_moment`: en "One moment, sir." / pt "Um momento, senhor."
  - `error`: en "I'm having trouble reaching my systems, sir." / pt "Estou com dificuldade para acessar meus sistemas, senhor."
- Secrets only from env / `.env`; never logged, never committed. Hermes ports never exposed publicly.
- One active turn per connection; one connection per device token (a new one replaces the old).

## Review Focus

1. The `[[DEEP]]` marker split across stream chunks (`"[[DE"`, `"EP]] ..."`) or preceded by whitespace must still be detected and never spoken. Test in Task 4.
2. An empty or noise-only transcript (silence, a cough) must not call Hermes. It returns to `idle` quietly. Test in Task 6.
3. A client disconnect or `interrupt` during the deep-ack line or mid-TTS cancels every in-flight task (no orphaned Hermes/ElevenLabs streams). Test in Task 6.
4. Audio frames or `interrupt` arriving before `hello`, or a bad token, close the socket with code 4401 and do no processing. Test in Task 7.
5. Reply text containing markdown (`**`, `#`, list bullets, URLs) is spoken as clean text, not read as symbols. Test in Task 3.

---

## File Structure

```
pyproject.toml              deps, pytest config
server/config.py            Settings (env-based)
server/protocol.py          message dataclasses + JSON encode/decode
server/text.py              clause chunker + speech cleanup
server/hermes_client.py     streaming /v1/chat/completions client
server/router.py            fast -> deep escalation, per-device history
server/stt.py               Transcriber (faster-whisper) + Endpointer
server/tts.py               ElevenLabs streaming synth + canned line cache
server/session.py           per-connection turn state machine
server/app.py               FastAPI: /ws, /health, auth
persona/SOUL.md             copied from spec Appendix A
deploy/Dockerfile, deploy/docker-compose.yml, deploy/Caddyfile,
deploy/install-hermes.sh, deploy/hermes-fast.service, deploy/hermes-deep.service,
deploy/.env.example, scripts/tokens.py
scripts/talk.py             wav -> server -> wav smoke client
scripts/bench.py            per-stage latency report
tests/...                   one test file per server module
```

---

### Task 1: Scaffold, config and protocol

**Files:**
- Create: `pyproject.toml`, `.gitignore`, `server/__init__.py`, `server/config.py`, `server/protocol.py`
- Test: `tests/test_config.py`, `tests/test_protocol.py`

**Interfaces:**
- Produces:
  - `Settings` (frozen dataclass) with `Settings.from_env(env: Mapping[str, str] = os.environ) -> Settings`. Fields: `elevenlabs_api_key: str`, `elevenlabs_voice_id: str`, `hermes_api_key: str`, `hermes_fast_url: str = "http://127.0.0.1:8642"`, `hermes_deep_url: str = "http://127.0.0.1:8643"`, `stt_model: str = "base"`, `device_tokens: dict[str, str]` (token -> device name, parsed from `DEVICE_TOKENS="win:tok1,android:tok2"`), `canned_dir: Path = Path("cache/canned")`.
  - In `protocol.py`: `parse_client(text: str) -> ClientMsg`, where `ClientMsg` is a union of `Hello(token, device)`, `EndUtterance()`, `Interrupt()`, `TextInput(text)`; raises `ProtocolError` on unknown or invalid input. Server messages are built by `state(s: str) -> str`, `transcript(text: str, lang: str, final: bool) -> str`, `reply_text(delta: str) -> str`, `level(mic: float, out: float) -> str`, `speaking_done() -> str` and `error(code: str, message: str) -> str`, each returning a JSON string with a `"type"` key equal to the function name.

- [ ] **Step 1: Init repo and project.** Run `git init`, then `uv init --python 3.12 --no-workspace`. Add deps: `fastapi uvicorn[standard] httpx websockets faster-whisper numpy` and dev deps `pytest pytest-asyncio`. Set `[tool.pytest.ini_options] asyncio_mode = "auto"`. `.gitignore` covers `.env`, `cache/`, `.venv/`, `__pycache__/`, `*.pcm`, `*.wav`.
- [ ] **Step 2: Write failing tests**

```python
def test_settings_parses_device_tokens():
    s = Settings.from_env({"ELEVENLABS_API_KEY": "k", "ELEVENLABS_VOICE_ID": "v",
                           "API_SERVER_KEY": "h", "DEVICE_TOKENS": "win:abc,android:def"})
    assert s.device_tokens == {"abc": "win", "def": "android"}
    assert s.hermes_fast_url == "http://127.0.0.1:8642"

def test_settings_missing_key_raises():
    with pytest.raises(KeyError):
        Settings.from_env({})

def test_parse_hello():
    assert parse_client('{"type":"hello","token":"t","device":"win"}') == Hello("t", "win")

def test_parse_rejects_unknown_and_garbage():
    for bad in ['{"type":"nope"}', 'not json', '{"type":"hello"}']:
        with pytest.raises(ProtocolError):
            parse_client(bad)

def test_server_messages_are_json():
    assert json.loads(state("thinking")) == {"type": "state", "state": "thinking"}
    assert json.loads(transcript("oi", "pt", True)) == {"type": "transcript", "text": "oi", "lang": "pt", "final": True}
```

- [ ] **Step 3: Run** `uv run pytest tests/test_config.py tests/test_protocol.py -v`. Expected: FAIL (import errors).
- [ ] **Step 4: Implement** `Settings` and `protocol.py` per the Interfaces block.
- [ ] **Step 5: Run the tests again.** Expected: PASS.
- [ ] **Step 6: Commit** `feat: project scaffold, settings and wire protocol`

---

### Task 2: Hermes streaming client

**Files:**
- Create: `server/hermes_client.py`
- Test: `tests/test_hermes_client.py`

**Interfaces:**
- Produces: `class HermesClient(base_url: str, api_key: str, http: httpx.AsyncClient | None = None)` with `async def stream(messages: list[dict]) -> AsyncIterator[str]`. It yields content deltas from SSE `data:` lines of `POST {base_url}/v1/chat/completions` with body `{"model": "hermes-agent", "messages": messages, "stream": True}`, header `Authorization: Bearer {api_key}`, and a 60 s read timeout. It stops at `data: [DONE]` and raises `HermesError` on a non-200 status or a timeout.

- [ ] **Step 1: Verify the live API shape.** Read `https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server`. Confirm the endpoint, the SSE delta format (`choices[0].delta.content`), and whether a session header exists. Note the findings in a comment at the top of the file. The router keeps its own history, so a session header is optional.
- [ ] **Step 2: Write failing tests** using `httpx.MockTransport`:

```python
async def test_stream_yields_deltas():
    body = ('data: {"choices":[{"delta":{"content":"Good "}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"evening"}}]}\n\n'
            'data: [DONE]\n\n')
    client = make_client(lambda req: httpx.Response(200, text=body))
    assert [d async for d in client.stream([{"role": "user", "content": "hi"}])] == ["Good ", "evening"]

async def test_stream_sends_auth_and_model():
    seen = {}
    def handler(req):
        seen["auth"] = req.headers["authorization"]; seen["body"] = json.loads(req.content)
        return httpx.Response(200, text="data: [DONE]\n\n")
    [_ async for _ in make_client(handler).stream([])]
    assert seen["auth"] == "Bearer key" and seen["body"]["model"] == "hermes-agent" and seen["body"]["stream"] is True

async def test_non_200_raises():
    with pytest.raises(HermesError):
        [_ async for _ in make_client(lambda r: httpx.Response(500)).stream([])]
```

- [ ] **Step 3: Run the tests.** Expected: FAIL. **Step 4: Implement** using `http.stream("POST", ...)` and `aiter_lines()`. **Step 5: Run the tests again.** Expected: PASS.
- [ ] **Step 6: Commit** `feat: streaming Hermes API client`

---

### Task 3: Speech text — clause chunker and cleanup

**Files:**
- Create: `server/text.py`
- Test: `tests/test_text.py`

**Interfaces:**
- Produces:
  - `clean_for_speech(text: str) -> str` strips markdown emphasis, headings, list bullets, code fences and inline code. It replaces URLs with their domain (`https://github.com/x` -> `github.com`) and collapses whitespace.
  - `class ClauseChunker` with `feed(delta: str) -> list[str]` and `flush() -> list[str]`. It emits a cleaned chunk at the first `, ; : . ! ?` (followed by a space or the end) once the buffer has at least **12 characters**, and from then on only at sentence ends `. ! ?`. Chunks are never empty.

- [ ] **Step 1: Write failing tests**

```python
def test_clean_strips_markdown_and_urls():
    assert clean_for_speech("**Done**, sir. See https://github.com/nous/x\n- item") == "Done, sir. See github.com item"

def test_first_chunk_breaks_early_at_comma():
    c = ClauseChunker()
    out = c.feed("Certainly sir, the weather ") + c.feed("today is mild. Anything else?")
    assert out == ["Certainly sir,", "the weather today is mild."]
    assert c.flush() == ["Anything else?"]

def test_short_comma_does_not_break():
    c = ClauseChunker()
    assert c.feed("Yes, ") == []

def test_flush_returns_remainder_and_never_empty():
    c = ClauseChunker(); c.feed("Hello there"); assert c.flush() == ["Hello there"]
    assert ClauseChunker().flush() == []
```

- [ ] **Step 2: Run the tests.** Expected: FAIL. **Step 3: Implement.** **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: clause chunker and speech text cleanup`

---

### Task 4: Router — fast/deep escalation with per-device history

**Files:**
- Create: `server/router.py`
- Test: `tests/test_router.py`

**Interfaces:**
- Consumes: anything with `stream(messages) -> AsyncIterator[str]` (`HermesClient` from Task 2).
- Produces:
  - `class Router(fast, deep, history_turns: int = 6)` with `async def ask(device: str, text: str, lang: str) -> AsyncIterator[RouterEvent]`.
  - `RouterEvent` is a union of `Escalated()` (yielded once, before any deep text) and `Delta(text: str)`.
  - The user message content is `f"[lang:{lang}] {text}"`. History is kept per device (the last `history_turns` user/assistant pairs) and sent to whichever profile answers. Only the final answer text is stored as the assistant turn, never `[[DEEP]]`.
  - The router buffers the fast stream until it has at least 8 non-whitespace chars or the stream ends. If the stripped buffer starts with `[[DEEP]]`, it cancels the fast stream (`aclose()`), yields `Escalated()`, then streams deep. Otherwise it yields the buffer and the rest as `Delta`s.

- [ ] **Step 1: Write failing tests** with a `FakeHermes(chunks: list[str])` that records the messages it receives:

```python
async def test_fast_answer_passes_through():
    fast, deep = FakeHermes(["Good ", "evening, sir."]), FakeHermes([])
    ev = [e async for e in Router(fast, deep).ask("win", "hello", "en")]
    assert "".join(e.text for e in ev if isinstance(e, Delta)) == "Good evening, sir."
    assert fast.received[-1]["content"] == "[lang:en] hello" and deep.calls == 0

async def test_split_marker_escalates_and_is_never_emitted():
    fast, deep = FakeHermes([" [[DE", "EP]]"]), FakeHermes(["Plano ", "pronto."])
    ev = [e async for e in Router(fast, deep).ask("android", "planeje", "pt")]
    assert isinstance(ev[0], Escalated)
    assert "".join(e.text for e in ev[1:]) == "Plano pronto."
    assert "[[DEEP]]" not in "".join(getattr(e, "text", "") for e in ev)

async def test_history_is_shared_across_profiles_and_capped():
    fast, deep = FakeHermes(["ok"]), FakeHermes([])
    r = Router(fast, deep, history_turns=2)
    for i in range(3):
        [_ async for _ in r.ask("win", f"q{i}", "en")]
    sent = fast.received_all[-1]
    assert [m["content"] for m in sent if m["role"] == "user"] == ["[lang:en] q1", "[lang:en] q2"]
```

- [ ] **Step 2: Run the tests.** Expected: FAIL. **Step 3: Implement.** **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: router with [[DEEP]] escalation and shared history`

---

### Task 5: STT and TTS adapters

**Files:**
- Create: `server/stt.py`, `server/tts.py`
- Test: `tests/test_stt.py`, `tests/test_tts.py`

**Interfaces:**
- Produces (`stt.py`):
  - `Transcript(text: str, lang: str)`.
  - `class Transcriber(model_size: str)` with `async def transcribe(pcm16: bytes) -> Transcript`. It runs `WhisperModel(...).transcribe` in `asyncio.to_thread`, using the Global Constraints settings. `lang` is `"pt"` if the detected language is `pt`, otherwise `"en"`.
  - `class Endpointer(sample_rate=16000, silence_ms=700, no_speech_ms=6000)` with `feed(pcm16: bytes) -> EndpointResult | None`, where `EndpointResult` is `"end"` or `"no_speech"`. It uses frame RMS energy with an adaptive noise floor (speech = RMS > 3 × floor), and is only the fallback, since clients send `end_utterance`.
- Produces (`tts.py`):
  - `class ElevenLabsTTS(api_key, voice_id)` with `async def synthesize(chunks: AsyncIterator[str], lang: str) -> AsyncIterator[bytes]`. It connects to `wss://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream-input?model_id=eleven_flash_v2_5&output_format=pcm_24000&language_code={lang}`, sends an initial `{"text": " ", "xi_api_key": ...}`, then each chunk with `"try_trigger_generation": true`, then `{"text": ""}`. It yields base64-decoded `audio` fields and raises `TTSError` on failure.
  - `class CannedLines(dir: Path, tts)` with `async def ensure()`, which synthesizes any missing file from the Global Constraints copy, and `get(key: str, lang: str) -> bytes`.

- [ ] **Step 1: Write failing tests.**
  - The STT test builds a `Transcriber` with an injected fake model (constructor kwarg `model=`) and asserts that `lang` maps to `"pt"`/`"en"` and that segment texts are joined and stripped.
  - Endpointer tests use synthetic numpy audio: 1 s of a 440 Hz tone at amplitude 8000, then 800 ms of zeros, gives `"end"`; 6.1 s of low noise (amplitude 50) gives `"no_speech"`; tone then 300 ms of zeros gives `None`.
  - TTS tests run a local `websockets.serve` fake that asserts the init message has `xi_api_key` and replies with two `{"audio": base64}` frames then `{"isFinal": true}`. Assert that the concatenated bytes match. Also assert that `CannedLines.ensure()` writes `one_moment_pt.pcm` using a fake tts.
- [ ] **Step 2: Run the tests.** Expected: FAIL. **Step 3: Implement.** The URL base is injectable for tests. **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: faster-whisper transcriber, endpointer, ElevenLabs streaming TTS`

---

### Task 6: Session — the turn state machine

**Files:**
- Create: `server/session.py`
- Test: `tests/test_session.py`

**Interfaces:**
- Consumes: `Transcriber.transcribe`, `Endpointer`, `Router.ask` + `Escalated`/`Delta`, `ClauseChunker`, `ElevenLabsTTS.synthesize`, `CannedLines.get`, and the `protocol.*` builders.
- Produces: `class Session(device: str, send_text: Callable[[str], Awaitable], send_bytes: Callable[[bytes], Awaitable], stt, router, tts, canned)` with these methods:
  - `async def on_audio(pcm16: bytes)` buffers the audio and feeds the endpointer, ending the utterance on `"end"` and going idle on `"no_speech"`.
  - `async def on_end_utterance()`.
  - `async def on_interrupt()`.
  - `async def on_text(text: str, lang: str = "en")`.
  - `async def close()` cancels everything.
- Turn flow:
  1. `state listening` on the first audio.
  2. `state thinking`.
  3. `transcript` (final).
  4. If the stripped text is under 2 chars, `state idle` and stop.
  5. Router deltas go through the chunker into TTS, and audio bytes are forwarded. `Escalated` sends `state deep` and plays `canned.get("ack_deep", lang)`. If no reply text arrives in 1.5 s, play `one_moment` (once). `reply_text` is sent for each delta. The first audio sends `state speaking`.
  6. `speaking_done` + `state idle`.
  7. On any exception: `error` message + canned `error` audio + `state idle`.
- The turn runs as one `asyncio.Task`. `on_interrupt` and `close` cancel it and send `state listening` (interrupt) or nothing (close).

- [ ] **Step 1: Write failing tests** with fakes (a STT returning a fixed `Transcript`, a router yielding scripted events with optional `asyncio.sleep`s, a TTS that turns each chunk into `b"A"*4`, and canned lines returning `b"C"*4`). Record everything sent.

```python
async def test_full_turn_message_order(): ...
    # states: listening, thinking, speaking, idle; transcript before reply_text; audio bytes present; speaking_done last
async def test_empty_transcript_skips_router(): ...      # router.calls == 0, ends in idle
async def test_escalation_sends_deep_state_and_ack_audio(): ...  # "deep" state, b"CCCC" before deep audio
async def test_one_moment_plays_once_after_1_5s(): ...   # router sleeps 2.0s before first delta; exactly one b"CCCC" before reply audio
async def test_interrupt_cancels_router_and_tts(): ...   # router stream gets aclose/cancel; no audio after interrupt; state listening
async def test_close_mid_turn_cancels_without_sending(): ...
async def test_router_error_speaks_error_line(): ...     # error msg + b"CCCC" + idle
async def test_level_messages_throttled(): ...           # 100 frames of 20 ms audio in -> at most 15 `level` msgs per second, mic in 0..1
```

Level events for the hologram: `level(mic, out)` = frame RMS / 32768 × 4, clipped to 1, sent at most every 66 ms, for incoming audio (mic) and outgoing TTS audio (out).

Use `asyncio` auto-mode. Patch the 1.5 s constant through a constructor kwarg `filler_after: float = 1.5` (tests pass 0.05).
- [ ] **Step 2: Run the tests.** Expected: FAIL. **Step 3: Implement.** **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: per-connection turn state machine`

---

### Task 7: FastAPI app — /ws, /health, auth

**Files:**
- Create: `server/app.py`
- Test: `tests/test_app.py`

**Interfaces:**
- Consumes: `Settings`, `parse_client`, `Session`, and the adapters from Tasks 2–5.
- Produces:
  - `create_app(settings: Settings, deps: Deps | None = None) -> FastAPI`. `Deps` bundles the stt/router/tts/canned instances so tests inject fakes. On startup it awaits `canned.ensure()`.
  - `GET /health` returns `{"ok": true}`.
  - `WS /ws`: the first message must be a text `hello` with a token in `settings.device_tokens`, or the socket closes with code **4401**. A new connection for the same device closes the previous one (code 4409). Text messages go to `Session` methods; binary messages go to `on_audio`.
  - Module-level `app = create_app(Settings.from_env())` lives in `server/main.py` for uvicorn.

- [ ] **Step 1: Write failing tests** with `fastapi.testclient.TestClient`:

```python
def test_health(): assert client.get("/health").json() == {"ok": True}
def test_bad_token_closes_4401(): ...           # send hello with wrong token -> WebSocketDisconnect code 4401
def test_audio_before_hello_closes_4401(): ...  # send_bytes first -> 4401
def test_text_turn_roundtrip(): ...             # hello, {"type":"text","text":"hi"} -> receive state/reply_text/bytes/speaking_done
def test_second_connection_replaces_first(): ...# first socket gets 4409
```

- [ ] **Step 2: Run the tests.** Expected: FAIL. **Step 3: Implement.** **Step 4: Run** `uv run pytest -v` (the whole suite). Expected: all PASS.
- [ ] **Step 5: Commit** `feat: websocket app with device-token auth`

---

### Task 8: Persona, deployment files and tools

**Files:**
- Create:
  - `persona/SOUL.md` (verbatim from spec Appendix A)
  - `deploy/Dockerfile`, `deploy/docker-compose.yml`, `deploy/Caddyfile`
  - `deploy/install-hermes.sh`, `deploy/hermes-fast.service`, `deploy/hermes-deep.service`
  - `deploy/.env.example`
  - `scripts/tokens.py`, `scripts/talk.py`, `scripts/bench.py`, `README.md`
- Test: `tests/test_tokens.py`

**Interfaces:**
- `scripts/tokens.py add <device>` prints a new 32-byte urlsafe token and rewrites `DEVICE_TOKENS` in a given `.env`; `revoke <device>` removes it.
- `scripts/talk.py --url wss://... --token T in.wav out.wav` streams a 16 kHz wav in 20 ms frames, sends `end_utterance`, writes the received audio to a 24 kHz wav, and prints transcript and reply text.
- `scripts/bench.py --url --token samples/*.wav` reports, per sample: end-of-upload → `transcript`, → first `reply_text`, → first audio byte, plus the median. It exits non-zero if the median to first audio is above 2.0 s.

Deployment details:
- **Dockerfile:** `python:3.12-slim`, `uv sync --frozen`, `CMD uvicorn server.main:app --host 0.0.0.0 --port 8000`, with a volume for `/root/.cache/huggingface` and `cache/`.
- **docker-compose.yml:** services `gateway` (`network_mode: host`, so it reaches `127.0.0.1:8642/8643`, and binds only `127.0.0.1:8000` via `--host 127.0.0.1`) and `caddy` (`network_mode: host`, ports 80/443).
- **Caddyfile:** `jarvis.openclawchip.tech { reverse_proxy 127.0.0.1:8000 }`.
- **install-hermes.sh:**
  - creates user `hermes` and runs the official Linux installer as that user;
  - creates profiles `fast` and `deep` with models `claude-haiku-5-5` and `claude-opus-5-5`;
  - sets `API_SERVER_ENABLED=true`, `API_SERVER_KEY`, and the port per profile (8642 and 8643, host 127.0.0.1);
  - copies `persona/SOUL.md` into both profiles;
  - enables both systemd units.
  - Exact profile and port flags: verify with `hermes --help` / docs at run time and record them in the script header.
- **ufw:** allow 22, 80, 443 only.

- [ ] **Step 1: Write a failing test** for `tokens.py`: `add` writes `DEVICE_TOKENS=win:<43 chars>`; adding `android` keeps `win`; `revoke win` leaves only android.
- [ ] **Step 2: Run the test.** Expected: FAIL. **Step 3: Implement** all files. **Step 4: Run the tests** (expected: PASS), then `docker build -f deploy/Dockerfile .` (Expected: builds).
- [ ] **Step 5: Commit** `feat: persona, deployment files, token, talk and bench tools`

---

### Task 9: Deploy to the VPS and verify (manual, with the user)

Needs from the user: SSH access, DNS A record `jarvis.openclawchip.tech` → VPS IP, `ANTHROPIC_API_KEY`, `ELEVENLABS_API_KEY`, the chosen ElevenLabs voice id. The user enters keys on the server themselves; nothing is pasted in chat.

- [ ] **Step 1:** Run `deploy/install-hermes.sh`. Check `curl -s 127.0.0.1:8642/health` and `:8643/health`. Expected: OK. A test `/v1/chat/completions` on each answers in character, and the fast profile answers a hard question with exactly `[[DEEP]]`.
- [ ] **Step 2:** `docker compose up -d`. Check `curl https://jarvis.openclawchip.tech/health`. Expected: `{"ok":true}` with a valid certificate. From outside, `nc -zv <ip> 8642` and `nc -zv <ip> 8000`. Expected: refused.
- [ ] **Step 3:** Run `scripts/talk.py` with one English and one Portuguese sample. Expected: correct transcripts, and replies in the same language and in character.
- [ ] **Step 4:** Run `scripts/bench.py` on 10 samples (5 en, 5 pt). Record the per-stage medians in `docs/latency.md`. If the median to first audio is over 2.0 s, stop and report (options: `tiny` model, 4 vCPU, a closer region) before any client work.
- [ ] **Step 5:** Check memory sharing. Tell the fast profile a fact, then ask something that escalates and depends on it. Record whether Hermes long-term memory is shared between profiles. In-conversation context is already shared by the router's history.
- [ ] **Step 6: Commit** `docs: deployment notes and measured latency`

---

## Deferred (only if Task 9 shows STT is the bottleneck)
Speculative STT (re-decoding a rolling window while the user speaks) is in the spec's latency plan but left out of v1. Whisper must still re-decode the full utterance at the end, so the gain is uncertain. Add it only if `bench.py` shows STT above 400 ms.
