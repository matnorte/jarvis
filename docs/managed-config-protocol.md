# Additive managed configuration and pairing

The user requested all Jarvis configuration in Hermes's native dashboard. These
extensions replace manual token entry/local preference editors without changing
PCM framing or turn, wake, barge-in, playback-drain or follow-up semantics.

## Public HTTPS pairing

Only `POST /pairing/request` and `POST /pairing/claim` are exposed through Caddy.
Both cap JSON bodies at 1 KiB, apply bounded per-peer rate limiting, return fixed
errors and `Cache-Control: no-store`, and never log bodies. Administration,
approval, environment and runtime-secret routes are not mounted publicly.

Each client generates a random 32-byte private claim capability in memory. Request
body: `{claim, device?}`. Omitted device means `android` for compatibility; the only
other accepted value is `win`. The request and its approval are bound to that type;
reusing a claim with another type fails. Response: `{id, expires_in}`. The identifier is public and shown
in both phone and owner dashboard; it is insufficient to claim a credential.
Control stores only the capability's SHA-256 digest, at most sixteen requests,
for three minutes. The owner must approve the matching identifier in Hermes.

Claim body: `{id, claim}`. Response is `{status: pending}` or `{status: expired}`
until a matching approved claim succeeds. Success includes the existing token for
the approved device type, its `device`, canonical server address, preference revision
and nonsecret settings. Windows never receives or replaces the Android credential.
Only a missing credential for that device type is created, leaving other tokens intact.
Android encrypts the token with Keystore; Windows uses safeStorage/DPAPI. Claims are
single-use; wrong capabilities do not consume an approval. Capability/token/key
values never belong in URL parameters, screenshots, logs or chat. The private
claim is never available to the dashboard browser.

## Authenticated WebSocket configuration

`hello` gains optional strict boolean `client_config`, default false. Legacy
clients keep their existing server-message flow. Opted-in phones receive:

```json
{"type":"config","revision":1,"settings":{"server_url":"wss://jarvis.openclawchip.tech/ws","wake_sensitivity":0.6,"end_silence_ms":700,"interrupt_by_speech":false,"start_on_boot":false,"vision_enabled":true,"front_camera":false}}
```

Client acknowledgement:

```json
{"type":"config_ack","revision":1,"idle":true}
```

Report idle only outside listening/thinking/speaking/follow-up, after local audio
drain. Report false when a new turn starts. Cache settings for offline wake; apply
new audio preferences only at a safe phase. Server dependency replacement requires
both this idle acknowledgement and no server capture/turn. Camera activation stays
an explicit local action. The acknowledgement never contains credentials.

The server prepares changed dependencies while preserving its last working
pipeline. Phone-only preferences preserve dependency objects and router history.
Unchanged model routing retains the router across voice-model changes. Managed
sessions update when safe; legacy sessions retain their existing dependencies
until reconnect. A failed revision gives a fixed apply error and is not retried
in a costly loop; saving again creates a fresh revision to retry.

The private control service communicates only over a root/Hermes-group Unix socket.
Hermes's authenticated plugin proxies only redacted settings/status and explicit
approval/decline. It cannot proxy private runtime credentials or phone claims.
Managed status reports Android and Windows connections/revisions separately; an
online PC must not make an offline phone appear connected. The shared preferences
and server resource updates still follow the same per-session safe-idle rules.
