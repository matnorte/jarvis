# Jarvis configuration in Hermes's native dashboard

User request, 2026-10-24: all Jarvis configuration belongs inside Hermes Agent's
own UI. This extends the vision plan. Keep the voice turn/barge-in/drain rules.

## Architecture

- Ship a standalone native Hermes dashboard plugin under this repo's `integrations/`.
  Use its documented manifest, React SDK and authenticated plugin API; never patch
  Hermes core or replace its chat. Native model/provider/profile pages remain the
  place for Hermes model credentials. Link them from the Jarvis tab.
- A private Jarvis control service owns validated canonical settings. Nonsecret
  settings live in a persisted JSON volume; API credentials remain in server `.env`.
  Dashboard reads return only configured/missing badges for secrets; replacements
  are write-only. Preserve existing env entries and never print request bodies.
- Connect the dashboard plugin and voice gateway to control over a Unix socket in
  a root/Hermes-only directory. No public administration port or admin credential
  in the browser. The existing Hermes session gate protects its plugin routes.
- Configure voice recognition/synthesis, routing profiles, public gateway address,
  wake sensitivity, endpoint silence, speech interruption, reboot reminder, vision
  availability and preferred camera in the Jarvis tab. Bounds remain those of the
  tested voice/camera implementation. Save/apply reports pending/applied status.
- A phone pairing screen creates a short-lived request using a strong private
  claim capability. It displays a public request identifier; the owner approves
  that matching request in Hermes. A single successful claim delivers the existing
  Android device credential over HTTPS directly into phone Keystore settings.
  No long-lived credential, private claim, or API key appears in chat, URLs, logs,
  screenshots, source, or temporary files. Bound request count, TTL, payloads and
  polling; wrong claims never consume a legitimate approval. Do not rotate existing
  device tokens merely to pair the phone.
- Optional authenticated server `config` messages synchronize nonsecret phone
  preferences. A client acknowledgement reports the applied revision. Legacy
  clients ignore the new server message. Defer pipeline changes until outside a
  voice turn/follow-up; preserve cached preferences for offline wake detection.
  New HTTP pairing messages and config messages are explicit additive protocol
  changes needed for centrally managed setup; binary PCM is untouched.
- The phone keeps pairing/connection status, cached preferences and Android OS
  permissions. All preference editors move to Hermes. Camera activation remains
  an explicit local action and stops on background/tab exit; web preferences must
  never silently activate the camera or microphone.

## Sequence (test first, full suites before each commit)

1. Canonical config store, masked secret updates and bounded approval/claim tests.
2. Private control API/service and authenticated native Hermes Jarvis tab, including
   save/apply, model/profile links and matching phone-request approval.
3. Gateway runtime loading, safe preference synchronization and public pairing
   relay; tests for unauthorized administration, old clients and secret redaction.
4. Android pairing/status UI, encrypted credential receipt and cached remote config;
   test expiry/retry, declined/wrong approval, safe-turn apply and camera shutdown.
5. Deploy without overwriting existing env secrets or prior server patches; inspect
   the real Hermes tab, pair the connected phone through it, verify Connected and
   continue Task 10 voice/vision/call/screen-off/battery checks. Record actual results.

## Checkpoint, 2026-10-24

Steps 1–4 are implemented with regression coverage: 180 backend, 103 JVM and 16
emulator tests pass, APKs build, lint has no errors. Web/control/gateway deployment
is complete. The native Hermes tab was visually reviewed and settings were saved,
persisted across profiles, and restored. Native plugin activation uses Hermes's
own config CLI for default/fast/deep; no core patch is required.

A synthetic device on the VPS completed actual native approval and one-use claim,
managed revision/idle acknowledgement, artificial live-image answer and spoken PCM.
No secret left the VPS. Step 5's physical-phone installation/pairing and manual
voice/vision/battery checks remain pending USB reconnection. They are not implied
by passing server/emulator tests.
