# Jarvis inside the native Hermes dashboard

This is a standalone dashboard plugin, using Hermes's documented drop-in manifest,
React SDK and authenticated API routes. It does not patch or fork Hermes core.
The Jarvis tab controls a single shared voice service and Android phone; these
settings intentionally remain the same when selecting the fast/deep agent profiles.
Hermes model/provider configuration stays on the native configuration pages linked
from the tab. The owner-operated dashboard now supports public HTTPS with native
Nous OAuth; see `docs/hermes-remote-access.md` for the required auth preflight.

Install/update on the VPS from the Jarvis checkout:

```bash
bash deploy/install-hermes-plugin.sh /home/hermes/.hermes
docker compose -f deploy/docker-compose.yml build gateway
docker compose -f deploy/docker-compose.yml up -d control gateway
docker compose -f deploy/docker-compose.yml up -d --force-recreate caddy
```

Reload Hermes and open `/jarvis`. Keep the backend on loopback behind the guarded
HTTPS proxy (or optional SSH tunnel). Its native OAuth gate guards the plugin. The plugin's browser
routes expose only redacted settings, status and explicit pairing approval/decline.
Private runtime credentials and phone claims are never proxied to that browser.

The installer enables the dashboard extension in default/fast/deep using Hermes's
native configuration CLI, preserving other plugin selections and environment
references. It requires the VPS system Python's PyYAML package. A manifest alone
does not enable a dashboard plugin on this Hermes version.

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

Android pairing and remote settings are implemented and tested. The native tab,
private control service and gateway synchronization are deployed. A server-side
synthetic device completed native approval → claim → managed config → live image
answer and spoken PCM. Physical-phone installation/pairing and the remaining
manual voice/battery checks are pending USB reconnection; see
`docs/superpowers/plans/2026-10-24-jarvis-hermes-control.md`.
