# Hermes from a phone, over public HTTPS

The owner requested access from anywhere on 2026-10-24. This supersedes the
SSH-only browser restriction; the dashboard backend, voice API and control socket
remain private. The intended browser address is
`https://jarvis.openclawchip.tech/jarvis?profile=fast`. Existing voice WebSocket,
health and pairing routes keep their current destinations.

**Deployed:** the owner provided their registered public OAuth client ID in a phone
screenshot. Native Hermes OAuth/public_url are configured and the guarded proxy is
active. HTTPS and loopback preflights pass; owner APIs require login, and chat,
terminal and event WebSockets reject unauthenticated access. Voice health passes.
The browser sign-in page was visually verified. The owner still needs to save the
Portal BASE URL below and complete the first authenticated phone login; successful
phone login/mobile-data operation have not yet been observed.

## Account setup

Use the official [Nous Portal Local Dashboards](https://portal.nousresearch.com/local-dashboards)
page. The owner completes personal sign-in and any Terms acceptance. In the Local
Dashboard editor, set **BASE URL** to `https://jarvis.openclawchip.tech` and save.
The Portal appends `/auth/callback` itself; do not enter that path in BASE URL.
The resulting public OAuth client
identifier belongs in native Hermes `dashboard.oauth.client_id`; it is not a device
token or provider API key. Do not paste passwords or access/refresh tokens in chat.

Alternatively, after personally signing the VPS Hermes install into Nous, register
using its native CLI (run as `hermes`):

```bash
hermes dashboard register --name Jarvis --redirect-uri https://jarvis.openclawchip.tech/auth/callback
```

Set native `dashboard.public_url` to `https://jarvis.openclawchip.tech` using the
Hermes configuration CLI. This exact origin engages authentication even with the
backend bound to loopback, permits its Host/Origin, and fixes the HTTPS callback.
Keep the backend on `127.0.0.1:9119`. Do not add `--insecure` or rewrite away the
browser's Host/Origin. Loopback Caddy is already a trusted proxy; no wildcard trust
is needed. Restart only `hermes-dashboard` to load the auth setup.

The official [Hermes authentication guide](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard#authentication-gated-mode)
recommends Nous/OIDC for public access. Its built-in shared-password provider is
for trusted networks/VPNs. Tailscale remains an alternative if the owner chooses
private access instead.

## Publication checks

From `/opt/jarvis` on the VPS, run:

```bash
python3 deploy/check-hermes-public.py http://127.0.0.1:9119 https://jarvis.openclawchip.tech
```

It requires native OAuth, denies unauthenticated owner/config/Jarvis APIs, and checks
that the Jarvis page redirects to login. It sends no credentials and prints no
response bodies. Failures block publication. Keep a copy of the current nonsecret
Caddyfile for rollback. Validate the candidate before replacing the active file:

```bash
docker compose -f deploy/docker-compose.yml cp deploy/Caddyfile.hermes caddy:/etc/caddy/Caddyfile.hermes
docker compose -f deploy/docker-compose.yml exec -T caddy caddy validate --config /etc/caddy/Caddyfile.hermes --adapter caddyfile
```

The active Compose bind contains only `deploy/Caddyfile`. Once validation and the
loopback preflight pass, install `deploy/Caddyfile.hermes` as `deploy/Caddyfile`,
force-recreate Caddy so its file bind sees the replacement, then verify:

```bash
python3 deploy/check-hermes-public.py https://jarvis.openclawchip.tech https://jarvis.openclawchip.tech
curl -fsS https://jarvis.openclawchip.tech/health
```

Test an unauthenticated WebSocket rejection too. Sign in personally on the phone,
verify Jarvis/config/chat and profile switching, save/restore a nonsecret preference,
and approve only the matching phone pairing request. Use Chrome's Add to Home screen
for convenient access. Check again on mobile data with Wi-Fi off. Do not claim these
checks passed until observed. Keep raw 9119/8642/8000 inaccessible from the internet.

The active `deploy/Caddyfile` now matches the validated public candidate. The old
SSH-only file is backed up on the VPS at `/root/jarvis-Caddyfile.ssh-only-20261024`.
Rollback requires restoring that file to `deploy/Caddyfile` and recreating Caddy;
restore/remove native `dashboard.public_url` only if deliberately returning to
loopback-only operation. No provider key or device token was changed. Native auth
configuration uses Hermes's CLI and stays on the server; Hermes core is unchanged.

## Windows regression commands

Checkpoint: 192 backend, 103 JVM and 16 emulator tests pass; debug/test APKs and lint pass. The
candidate Caddyfile validates on the VPS. The preflight first refused the old
unauthenticated setup, then passed on loopback and public HTTPS after native OAuth
was configured. The native login uses the exact HTTPS callback and PKCE S256.

Changed files: `deploy/Caddyfile`, `deploy/Caddyfile.hermes`,
`deploy/hermes-dashboard.service`, `deploy/check-hermes-public.py`,
`tests/test_hermes_public_access.py` and deployment/handoff documentation. No Android
or voice semantics change. The checker tests reject disabled/invalid gates,
password-only login, exposed owner APIs and accidental response disclosure.

```bash
python -m uv run --python 3.12 pytest -W error -q
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
./gradlew --stop
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.openclawchip.jarvis.test/androidx.test.runner.AndroidJUnitRunner
```
