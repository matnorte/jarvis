"""Enable the dashboard-only Jarvis extension through Hermes's native config writer.

Run on the VPS as root. Read only the plugin-selection fields, preserve existing
selections, and never print configuration dictionaries or CLI diagnostics.
"""
import json
from pathlib import Path
import subprocess
import sys

import yaml


def enable(home: Path, executable: str):
    for profile in ('default', 'fast', 'deep'):
        directory = home if profile == 'default' else home / 'profiles' / profile
        if not directory.is_dir():
            continue
        path = directory / 'config.yaml'
        raw = yaml.safe_load(path.read_text()) if path.exists() else {}
        plugins = (raw or {}).get('plugins') or {}
        enabled, disabled = plugins.get('enabled') or [], plugins.get('disabled') or []
        if not isinstance(enabled, list) or not isinstance(disabled, list):
            raise RuntimeError('Invalid native plugin selection; existing configuration left unchanged')
        updates = {'enabled': sorted(set(enabled) | {'jarvis'}), 'disabled': sorted(set(disabled) - {'jarvis'})}
        for field, value in updates.items():
            if value == plugins.get(field, []):
                continue
            result = subprocess.run(['runuser', '-u', 'hermes', '--', executable, '--profile', profile,
                                     'config', 'set', f'plugins.{field}', json.dumps(value)], capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(f'Native Jarvis plugin activation failed for profile {profile}')
        confirmed = yaml.safe_load(path.read_text()).get('plugins') or {}
        if 'jarvis' not in confirmed.get('enabled', []) or 'jarvis' in confirmed.get('disabled', []):
            raise RuntimeError(f'Native Jarvis plugin activation was not persisted for profile {profile}')
        print(f'{profile}: Jarvis dashboard extension enabled')


if __name__ == '__main__':
    enable(Path(sys.argv[1]), sys.argv[2])
