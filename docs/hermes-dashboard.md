# Hermes's native web dashboard

The existing Hermes v0.22.5 installation runs as user `hermes` on the VPS. Its
official dashboard now runs under `hermes-dashboard.service`, bound only to
`127.0.0.1:9119`. This is separate from the voice API gateway on 8642 and does not
replace or restart it. No public admin port or reverse-proxy route was added.

Open an SSH tunnel in Windows PowerShell or Git Bash:

```bash
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:9119:127.0.0.1:9119 root@187.127.57.170
```

Keep that terminal running, then open:

- [Fast profile configuration](http://127.0.0.1:9119/config?profile=fast)
- [Deep profile configuration](http://127.0.0.1:9119/config?profile=deep)

The profile selector chooses which configuration, models, tools, and settings you
are editing. Save in the dashboard. Model/session changes generally apply to a new
agent session; settings loaded by the gateway may need its Restart Gateway action.
Avoid changing the deployed fast/deep routing setup without testing voice afterward.

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

This is an SSH-authenticated local interface. Do not bind it to a public address or
proxy it publicly without configuring an appropriate Hermes authentication provider.

Official guide: [Hermes Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard).
