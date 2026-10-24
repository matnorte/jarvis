#!/usr/bin/env bash
set -euo pipefail

# Run as root from a deployed checkout. Pass the installed Hermes home explicitly.
if [[ $# != 1 || "$1" != /* || ! -d "$1" ]]; then
  echo "Usage: $0 /absolute/path/to/hermes-home" >&2
  exit 2
fi
jarvis_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
hermes_home=$(realpath -e -- "$1")
plugin_dir="$hermes_home/plugins/jarvis/dashboard"
if [[ ! -d "$hermes_home/plugins" ]]; then
  install -d -o hermes -g hermes -m 0700 "$hermes_home/plugins"
fi
install -d -o hermes -g hermes -m 0755 "$hermes_home/plugins/jarvis" "$plugin_dir" "$plugin_dir/dist"
install -o hermes -g hermes -m 0644 "$jarvis_root/integrations/hermes-jarvis/dashboard/manifest.json" "$plugin_dir/manifest.json"
install -o hermes -g hermes -m 0644 "$jarvis_root/integrations/hermes-jarvis/dashboard/plugin_api.py" "$plugin_dir/plugin_api.py"
install -o hermes -g hermes -m 0644 "$jarvis_root/integrations/hermes-jarvis/dashboard/dist/index.js" "$plugin_dir/dist/index.js"
install -o hermes -g hermes -m 0644 "$jarvis_root/integrations/hermes-jarvis/dashboard/dist/style.css" "$plugin_dir/dist/style.css"
install -o root -g root -m 0644 "$jarvis_root/deploy/jarvis-control.tmpfiles.conf" /etc/tmpfiles.d/jarvis-control.conf
systemd-tmpfiles --create /etc/tmpfiles.d/jarvis-control.conf
hermes_account_home=$(getent passwd hermes | cut -d: -f6)
python3 "$jarvis_root/deploy/enable-hermes-plugin.py" "$hermes_home" "$hermes_account_home/.local/bin/hermes"
systemctl restart hermes-dashboard
echo "Installed Jarvis dashboard plugin and private socket directory."
