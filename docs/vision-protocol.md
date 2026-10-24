# Live camera protocol extension

Added at the user's request for live camera questions. Voice-only clients and
Conversation's turn, pre-roll, interruption and playback-drain rules are unchanged.
Binary WebSocket messages remain PCM16 audio. Camera uses separate JSON text frames
after the normal authenticated hello, over the same WSS connection.

```json
{"type":"camera","enabled":true}
{"type":"camera_frame","jpeg":"BASE64_JPEG"}
{"type":"camera","enabled":false}
```

Camera defaults off for every connection. Turning it on starts with no saved view;
turning it off or closing the connection clears the gateway's latest-frame buffer.
Frames arriving while off are ignored. Reconnecting requires enabling the camera
again; an old connection's image never transfers to a new one.

- Client target: at most two JPEG frames/second, long edge 640 px, bounded send queue.
- Server limit: 128 KiB decoded JPEG, maximum 1280 px per edge, strict base64/JPEG
  decoding. Frames faster than one every 400 ms are dropped before image decoding.
- Only the latest accepted frame is held by Jarvis in memory. It expires after four
  seconds. Camera image payloads are excluded from dataclass repr and logs.
- A spoken question snapshots the current view when `end_utterance` arrives. Text
  protocol clients snapshot it when sending their question. The stream may continue
  refreshing while that answer runs, without generating unsolicited model calls.
- Camera on without a fresh frame produces `error` code `camera_unavailable` and
  the existing spoken-error/idle flow, rather than answering without visual context.
- Malformed frame: `bad_camera_frame` / `Invalid camera frame`, no payload echoed;
  the authenticated socket remains usable. Invalid control shape is `bad_message`.
- Current-question image context is sent to Hermes as standard `text` + `image_url`
  content with an inline JPEG data URL. It follows fast→deep escalation when needed.
  Jarvis records only question/answer text in its own history, never image bytes.

The selected Hermes model receives the image for the question. Hermes's own session,
image-cache and provider retention settings still apply independently of Jarvis's
in-memory frame buffer. Images are not saved to disk by the Jarvis camera gateway.

The persona instructs both profiles to use only an attached current image as visual
evidence, acknowledge unclear views, and treat instructions in pictures as scene
content rather than commands.
