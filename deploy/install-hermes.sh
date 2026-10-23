#!/usr/bin/env bash
# Install Hermes Agent for Jarvis: user `hermes`, profiles fast and deep, served by ONE
# host gateway on 127.0.0.1:8642 at /p/fast and /p/deep (Hermes >= v0.21 allows only one
# gateway per host; per-profile `hermes -p NAME gateway` refuses to start).
#
# The LLM is OpenAI via ChatGPT/Codex subscription OAuth (provider `openai-codex`):
# fast = gpt-5.6-luna, deep = gpt-6.1-sol (override with FAST_MODEL / DEEP_MODEL).
# No LLM API key is needed; after this script, log in once as the hermes user:
#   sudo -u hermes -H /home/hermes/.local/bin/hermes auth add openai-codex
# (device-code flow, works over SSH). Both profiles share that login.
#
# Run as root from the repo root, after putting API_SERVER_KEY in a root-only file
# (mode 600; default /root/jarvis-secrets.env, override with SECRETS_FILE):
#   sudo bash deploy/install-hermes.sh
# Do NOT pass the key inline on the command line (it would land in ps, shell history
# and auth.log). Secrets are never taken as arguments and never printed.
#
# --- Verified against https://hermes-agent.nousresearch.com/docs (2026-10-23) ---
# - Installer: curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
#   (flag --non-interactive exists; data dir is ~/.hermes, CLI at ~/.local/bin/hermes)
# - Profiles: `hermes profile create <name>`; `hermes -p <name> ...`; each profile
#   lives in ~/.hermes/profiles/<name> with its own .env, config.yaml and SOUL.md.
# - Per-profile `<name> config set <key> <value>` / `hermes -p <name> config set ...`.
# - API server env vars: API_SERVER_ENABLED, API_SERVER_PORT, API_SERVER_HOST (default
#   127.0.0.1), API_SERVER_KEY. With gateway.multiplex_profiles the host gateway (default
#   profile, ~/.hermes/.env) owns the listener and serves each profile at /p/<profile>/v1/...;
#   each profile must have its OWN API_SERVER_KEY in its profile .env.
# - Start command (foreground, for systemd): `hermes gateway run` as the hermes user.
# - SOUL.md is read from $HERMES_HOME/SOUL.md (profile dir for a profile).
# - Verified on v0.22.5 (2026-10-23): /p/fast and /p/deep answer; requests may send any of
#   "hermes-agent" or the profile name as `model`.
# - Provider `openai-codex` ("ChatGPT or Codex Subscription"): `hermes auth add openai-codex`
#   uses a device code by default; credentials go to ~/.hermes/auth.json and OAuth logins
#   in the root auth.json are shared by every profile. Codex models include gpt-5.6-luna,
#   gpt-5.6-terra, gpt-5.6-sol and gpt-5.5. VERIFY: gpt-6.1-sol (released 2026-09-29, in Codex) is
#   not on that docs page yet; check `hermes -p deep model` lists it.
#
# --- NOT verified (marked `# VERIFY:` below; check with `hermes --help` / docs) ---
# - The config keys `model.provider` / `model.default` (docs show a `model:` section with
#   `provider` and `default`; setting them with `config set` is inferred).
# - Whether `hermes profile create` is non-interactive and whether memory is shared
#   between profiles (spec: verify during build).
# - That the installer works unattended with only `--non-interactive`.
set -euo pipefail

SECRETS_FILE="${SECRETS_FILE:-/root/jarvis-secrets.env}"
PROVIDER=openai-codex
FAST_MODEL="${FAST_MODEL:-gpt-5.6-luna}"
DEEP_MODEL="${DEEP_MODEL:-gpt-6.1-sol}"
HERMES_USER=hermes
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOUL_FAST="$REPO_DIR/persona/SOUL.md"       # includes the [[DEEP]] routing rule
SOUL_DEEP="$REPO_DIR/persona/SOUL.deep.md"  # same persona, no routing rule

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
for f in "$SOUL_FAST" "$SOUL_DEEP"; do
  [ -f "$f" ] || { echo "missing $f (run from the repo)" >&2; exit 1; }
done

# Secret: environment first, then a root-only env file.
if [ -z "${API_SERVER_KEY:-}" ]; then
  if [ -f "$SECRETS_FILE" ]; then
    if [ "$(stat -c '%a' "$SECRETS_FILE")" != "600" ]; then
      echo "$SECRETS_FILE must be mode 600" >&2; exit 1
    fi
    set +x
    set -a; . "$SECRETS_FILE"; set +a
  fi
fi
: "${API_SERVER_KEY:?set API_SERVER_KEY in the environment or $SECRETS_FILE}"

# 1. Service user.
id "$HERMES_USER" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$HERMES_USER"
HH="$(getent passwd "$HERMES_USER" | cut -d: -f6)"
HERMES_BIN="$HH/.local/bin/hermes"

# 2. Official installer, as the hermes user (skipped if already installed).
if [ ! -x "$HERMES_BIN" ]; then
  # VERIFY: installer flags; --non-interactive is documented, others were not needed.
  sudo -u "$HERMES_USER" -H bash -c \
    'curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive'
fi
[ -x "$HERMES_BIN" ] || { echo "hermes not found at $HERMES_BIN after install" >&2; exit 1; }

hermes_as() { sudo -u "$HERMES_USER" -H env "PATH=$HH/.local/bin:$PATH" "$@"; }

# 3. Profiles.
setup_profile() {
  local name="$1" model="$2" soul="$3"
  local pdir="$HH/.hermes/profiles/$name"

  if [ ! -d "$pdir" ]; then
    # VERIFY: non-interactive behaviour of `hermes profile create`.
    hermes_as hermes profile create "$name"
  fi

  # VERIFY: config keys model.provider / model.default (docs: `model:` section with provider, default).
  hermes_as hermes -p "$name" config set model.provider "$PROVIDER"
  hermes_as hermes -p "$name" config set model.default "$model"

  # Persona: fast gets the routing rule; deep must never answer [[DEEP]].
  install -o "$HERMES_USER" -g "$HERMES_USER" -m 644 "$soul" "$pdir/SOUL.md"

  # The profile's own API key (required for /p/<name>/ under the host gateway).
  write_api_env "$pdir/.env" "API_SERVER_ENABLED=true"
}

# Rewrite an .env (mode 600, owned by hermes): keep unrelated lines, drop old API_SERVER_*
# and stale ANTHROPIC_API_KEY lines, append API_SERVER_KEY plus the given lines. Secrets
# come from variables, never from command-line arguments. The LLM login is OAuth.
write_api_env() {
  local envf="$1" tmp; shift
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  [ -f "$envf" ] && grep -vE '^(API_SERVER_[A-Z_]+|ANTHROPIC_API_KEY)=' "$envf" >"$tmp" || true
  { printf '%s\n' "$@"; echo "API_SERVER_KEY=$API_SERVER_KEY"; } >>"$tmp"
  install -o "$HERMES_USER" -g "$HERMES_USER" -m 600 "$tmp" "$envf"
  rm -f "$tmp"
}

setup_profile fast "$FAST_MODEL" "$SOUL_FAST"
setup_profile deep "$DEEP_MODEL" "$SOUL_DEEP"

# 4. One host gateway: the default profile owns the API listener on 127.0.0.1:8642 and
#    multiplexes every profile under /p/<profile>/.
write_api_env "$HH/.hermes/.env" \
  "API_SERVER_ENABLED=true" "API_SERVER_HOST=127.0.0.1" "API_SERVER_PORT=8642"
hermes_as hermes config set gateway.multiplex_profiles true

# 5. systemd unit (replaces the old per-profile units). Started once the Codex login exists.
systemctl disable --now hermes-fast.service hermes-deep.service 2>/dev/null || true
rm -f /etc/systemd/system/hermes-fast.service /etc/systemd/system/hermes-deep.service
install -m 644 "$REPO_DIR/deploy/hermes-gateway.service" /etc/systemd/system/hermes-gateway.service
systemctl daemon-reload
systemctl enable hermes-gateway.service
LOGGED_IN=no
grep -q '"openai-codex"' "$HH/.hermes/auth.json" 2>/dev/null && LOGGED_IN=yes

# 6. Firewall: only 22, 80, 443 (Hermes binds 127.0.0.1 anyway).
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
fi

if [ "$LOGGED_IN" = yes ]; then
  systemctl restart hermes-gateway.service
  echo "Done. Check (with the key in a header file): curl -H @hdr http://127.0.0.1:8642/p/fast/v1/models"
else
  echo "Installed. Next, log in to OpenAI Codex (device code), then start the gateway:"
  echo "  sudo -u $HERMES_USER -H $HERMES_BIN auth add openai-codex"
  echo "  systemctl restart hermes-gateway.service"
fi
