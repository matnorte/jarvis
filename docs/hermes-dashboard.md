# Hermes's native web dashboard

Public phone access is live at
[Hermes → Jarvis](https://jarvis.openclawchip.tech/jarvis?profile=fast), protected by
native Nous sign-in. The owner must save `https://jarvis.openclawchip.tech` in the
Portal dashboard's BASE URL, then sign in with the same account. See
[remote access](hermes-remote-access.md) for verification and recovery. The tunnel
below is optional and now also reaches the same OAuth-protected dashboard.

The existing Hermes v0.22.5 installation runs as user `hermes` on the VPS. Its
official dashboard now runs under `hermes-dashboard.service`, bound only to
`127.0.0.1:9119`. This is separate from the voice API gateway on 8642 and does not
replace or restart it. Caddy provides public HTTPS on the existing domain, keeping
the raw backend port private. Native public_url engages the OAuth gate on loopback.

Open an SSH tunnel in Windows PowerShell or Git Bash:

```bash
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:9119:127.0.0.1:9119 root@187.127.57.170
```

Keep that terminal running, then open:

- [Jarvis configuration and phone approval](http://127.0.0.1:9119/jarvis?profile=fast)
- [Fast profile configuration](http://127.0.0.1:9119/config?profile=fast)
- [Deep profile configuration](http://127.0.0.1:9119/config?profile=deep)

The profile selector chooses which configuration, models, tools, and settings you
are editing. Save in the dashboard. Model/session changes generally apply to a new
agent session; settings loaded by the gateway may need its Restart Gateway action.
Avoid changing the deployed fast/deep routing setup without testing voice afterward.

The native **Jarvis** tab owns all Jarvis preferences: STT/TTS, voice and write-only
provider key replacement, listening, startup reminder, vision, routing profiles and
gateway address. These settings belong to the shared Jarvis service across profiles.
Changes wait for a safe idle point after the current turn and follow-up. Native
Hermes Config continues to own agent models, provider authentication and tools.

On the phone, open Settings → Pair with Hermes. Approve only the matching public
request identifier in this tab within three minutes. No device token needs copying.
Android permissions and Start camera remain explicit phone actions. Stored keys
show configured/missing badges and never return to the form. Details and recovery:
`hermes-jarvis-control.md`.

The running app-created SSH tunnel is temporary. The VPS systemd service survives
reboot; reconnect the SSH tunnel when needed. The current config page was verified
in the browser, including the fast profile and its configured model. No API key
values, environment files or bootstrap tokens were printed.

Deployment/recovery (on the VPS):

```bash
systemctl is-active hermes-dashboard
systemctl restart hermes-dashboard
ss -lnt | grep 9119
```

Install/update the unit from `deploy/hermes-dashboard.service` as root and run
`systemctl daemon-reload` then `systemctl enable --now hermes-dashboard`.
The unit's Node path matches the installed Hermes-managed runtime; update that path
if a later Hermes upgrade relocates Node. Standard output/error go to null because
the native dashboard can print local bootstrap credentials during startup.

The backend stays bound to loopback. Native Nous OAuth protects both public and
tunneled requests; preserve it when updating the proxy or service.

Official guide: [Hermes Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard).
