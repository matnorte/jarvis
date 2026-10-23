#!/usr/bin/env bash
# Install Hermes Agent for Jarvis: user `hermes`, profiles fast (8642) and deep (8643).
#
# Run as root from the repo root, after putting ANTHROPIC_API_KEY and API_SERVER_KEY in a
# root-only file (mode 600; default /root/jarvis-secrets.env, override with SECRETS_FILE):
#   sudo bash deploy/install-hermes.sh
# Do NOT pass the keys inline on the command line (they would land in ps, shell history
# and auth.log). Secrets are never taken as arguments and never printed.
#
# --- Verified against https://hermes-agent.nousresearch.com/docs (2026-10-23) ---
# - Installer: curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
#   (flag --non-interactive exists; data dir is ~/.hermes, CLI at ~/.local/bin/hermes)
# - Profiles: `hermes profile create <name>`; `hermes -p <name> ...`; each profile
#   lives in ~/.hermes/profiles/<name> with its own .env, config.yaml and SOUL.md.
# - Per-profile `<name> config set <key> <value>` / `hermes -p <name> config set ...`.
# - API server env vars (in the profile's .env): API_SERVER_ENABLED, API_SERVER_PORT,
#   API_SERVER_HOST (default 127.0.0.1), API_SERVER_KEY, API_SERVER_MODEL_NAME.
# - Start command: `hermes -p <name> gateway`.
# - SOUL.md is read from $HERMES_HOME/SOUL.md (profile dir for a profile).
# - By default the API model name is the PROFILE name, so we set
#   API_SERVER_MODEL_NAME=hermes-agent to match what the gateway sends.
#
# --- NOT verified (marked `# VERIFY:` below; check with `hermes --help` / docs) ---
# - Exact provider/model identifier syntax for Anthropic claude-haiku-5-5 / claude-opus-5-5
#   (docs example shows `anthropic/claude-opus-4`; we assume `model.default anthropic/<id>`).
# - That ANTHROPIC_API_KEY in the profile .env is the variable the Anthropic provider reads.
# - Whether `hermes profile create` is non-interactive and whether memory is shared
#   between profiles (spec: verify during build).
# - That the installer works unattended with only `--non-interactive`.
set -euo pipefail

SECRETS_FILE="${SECRETS_FILE:-/root/jarvis-secrets.env}"
HERMES_USER=hermes
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOUL_SRC="$REPO_DIR/persona/SOUL.md"

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
[ -f "$SOUL_SRC" ] || { echo "missing $SOUL_SRC (run from the repo)" >&2; exit 1; }

# Secrets: environment first, then a root-only env file.
if [ -z "${ANTHROPIC_API_KEY:-}" ] || [ -z "${API_SERVER_KEY:-}" ]; then
  if [ -f "$SECRETS_FILE" ]; then
    if [ "$(stat -c '%a' "$SECRETS_FILE")" != "600" ]; then
      echo "$SECRETS_FILE must be mode 600" >&2; exit 1
    fi
    set +x
    set -a; . "$SECRETS_FILE"; set +a
  fi
fi
: "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY in the environment or $SECRETS_FILE}"
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
  local name="$1" model="$2" port="$3"
  local pdir="$HH/.hermes/profiles/$name"

  if [ ! -d "$pdir" ]; then
    # VERIFY: non-interactive behaviour of `hermes profile create`.
    hermes_as hermes profile create "$name"
  fi

  # VERIFY: model identifier format for Anthropic models (docs example: anthropic/claude-opus-4).
  hermes_as hermes -p "$name" config set model.default "anthropic/$model"

  # Persona: same SOUL.md in both profiles.
  install -o "$HERMES_USER" -g "$HERMES_USER" -m 644 "$SOUL_SRC" "$pdir/SOUL.md"

  # Secrets + API server settings go straight into the profile .env (mode 600),
  # written from variables, never via command-line arguments.
  local envf="$pdir/.env" tmp
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  [ -f "$envf" ] && grep -vE '^(API_SERVER_[A-Z_]+|ANTHROPIC_API_KEY)=' "$envf" >"$tmp" || true
  {
    echo "API_SERVER_ENABLED=true"
    echo "API_SERVER_HOST=127.0.0.1"
    echo "API_SERVER_PORT=$port"
    echo "API_SERVER_MODEL_NAME=hermes-agent"
    echo "API_SERVER_KEY=$API_SERVER_KEY"
    # VERIFY: ANTHROPIC_API_KEY is the variable the Anthropic provider reads.
    echo "ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY"
  } >>"$tmp"
  install -o "$HERMES_USER" -g "$HERMES_USER" -m 600 "$tmp" "$envf"
  rm -f "$tmp"
}

setup_profile fast claude-haiku-5-5 8642
setup_profile deep claude-opus-5-5 8643

# 4. systemd units.
install -m 644 "$REPO_DIR/deploy/hermes-fast.service" /etc/systemd/system/hermes-fast.service
install -m 644 "$REPO_DIR/deploy/hermes-deep.service" /etc/systemd/system/hermes-deep.service
systemctl daemon-reload
systemctl enable --now hermes-fast.service hermes-deep.service

# 5. Firewall: only 22, 80, 443 (Hermes binds 127.0.0.1 anyway).
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
fi

echo "Done. Check: curl -s http://127.0.0.1:8642/health ; curl -s http://127.0.0.1:8643/health"
