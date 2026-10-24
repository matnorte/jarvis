# Jarvis inside the native Hermes dashboard

This is a standalone dashboard plugin, using Hermes's documented drop-in manifest,
React SDK and authenticated API routes. It does not patch or fork Hermes core.
The Jarvis tab controls a single shared voice service and Android phone; these
settings intentionally remain the same when selecting the fast/deep agent profiles.
Hermes model/provider configuration stays on the native configuration pages linked
from the tab. The deployment is for the existing owner-operated SSH-only dashboard.

Install/update on the VPS from the Jarvis checkout:

```bash
bash deploy/install-hermes-plugin.sh /home/hermes/.hermes
docker compose -f deploy/docker-compose.yml build gateway
docker compose -f deploy/docker-compose.yml up -d control gateway
docker compose -f deploy/docker-compose.yml up -d --force-recreate caddy
```

Reload Hermes and open `/jarvis`. Keep the dashboard on loopback behind the existing
SSH tunnel. Its normal session gate also guards the plugin. The plugin's browser
routes expose only redacted settings, status and explicit pairing approval/decline.
Private runtime credentials and phone claims are never proxied to that browser.

The control service listens only on `/run/jarvis/control.sock` inside a root/Hermes
group directory, has no TCP listener/network access, and persists nonsecret settings
in the existing gateway volume. Keys remain in the existing server deployment `.env`;
replacement is write-only, preserving unrelated entries. Never print request bodies.

Pairing uses an opaque private claim capability held by the phone and a public
request identifier shown in both UIs. The owner must match and approve that request.
Requests expire after three minutes and are bounded to sixteen; a valid claim is
single-use. Public HTTPS routes only request/claim; they cannot approve, read runtime
secrets or edit settings. The existing Android device token is reused, not rotated.

Phones opt into optional `config` messages using `hello.client_config`. They report
the applied revision and idle state with `config_ack`; server Session changes require
that acknowledgement plus no capture/turn in progress. Phone-only changes preserve
the existing server dependency objects and router history. Cached phone preferences
continue to support offline wake detection. Legacy clients retain their old wire flow.

The Android pairing/remote-settings UI and final deployment verification are still
in progress; see `docs/superpowers/plans/2026-10-24-jarvis-hermes-control.md`.
