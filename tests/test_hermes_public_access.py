import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

import pytest


@pytest.fixture
def checker():
    path = Path(__file__).parents[1] / 'deploy/check-hermes-public.py'
    spec = importlib.util.spec_from_file_location('hermes_public_check', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def dashboard():
    state = {'auth_required': True, 'auth_providers': ['nous'], 'exposed': None, 'redirect': '/login'}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/api/status':
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps(state).encode())
            elif self.path.startswith('/jarvis'):
                self.send_response(302)
                self.send_header('Location', state['redirect'])
                self.end_headers()
            else:
                exposed = self.path == state['exposed']
                self.send_response(200 if exposed else 401)
                self.end_headers()
                self.wfile.write(b'private-response-must-not-be-reported')

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f'http://127.0.0.1:{server.server_port}', state
    server.shutdown()
    server.server_close()
    thread.join()


def test_publication_requires_real_gate_and_denied_owner_apis(checker, dashboard):
    address, _ = dashboard
    checker.check_access(address, 'https://jarvis.openclawchip.tech')


def test_native_single_provider_automatic_sso_redirect_is_allowed(checker, dashboard):
    address, state = dashboard
    state['redirect'] = '/auth/login?provider=nous&next=%2Fjarvis'
    checker.check_access(address, 'https://jarvis.openclawchip.tech')


@pytest.mark.parametrize('change', [
    {'auth_required': False},
    {'auth_required': 'true'},
    {'auth_providers': []},
    {'auth_providers': ['basic']},
    {'auth_providers': ['nous', 'basic']},
    {'exposed': '/api/config'},
    {'exposed': '/api/plugins/jarvis/state'},
    {'exposed': '/api/auth/me'},
    {'redirect': 'https://attacker.example/login'},
    {'redirect': '/jarvis'},
])
def test_fail_closed_without_echoing_private_responses(checker, dashboard, change):
    address, state = dashboard
    state.update(change)
    with pytest.raises(checker.AccessCheckError) as error:
        checker.check_access(address, 'https://jarvis.openclawchip.tech')
    assert 'private-response' not in str(error.value)
