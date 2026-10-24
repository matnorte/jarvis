import httpx
import pytest

from server.control import create_control_app
from server.control_store import ControlStore


@pytest.fixture
def store(tmp_path):
    env = tmp_path / '.env'
    env.write_text('ELEVENLABS_API_KEY=test-secret-key\nELEVENLABS_VOICE_ID=voice\nAPI_SERVER_KEY=test-hermes-key\nDEVICE_TOKENS=android:test-device-key\n')
    return ControlStore(tmp_path / 'settings.json', env)


async def test_dashboard_views_are_redacted_and_errors_never_echo_secret_inputs(store):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_control_app(store)), base_url='http://control') as client:
        response = await client.get('/settings')
        assert response.status_code == 200
        assert 'test-secret-key' not in response.text and 'test-device-key' not in response.text
        bad = await client.put('/settings', json={'settings': {'wake_sensitivity': 'private-input'}, 'elevenlabs_key': 'new-private-input'})
        assert bad.status_code == 400 and 'private-input' not in bad.text
        too_large = await client.put('/settings', content=b'x' * 9000)
        assert too_large.status_code == 413
        updated = await client.put('/settings', json={'settings': {'start_on_boot': True}})
        assert updated.status_code == 200 and updated.json()['settings']['start_on_boot']
        assert updated.headers['cache-control'] == 'no-store'


async def test_pairing_routes_separate_owner_approval_from_private_phone_claim(store):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_control_app(store)), base_url='http://control') as client:
        claim = 'a' * 43
        request = (await client.post('/pairing/request', json={'claim': claim})).json()
        key = request['id']
        pending = (await client.get('/pairing')).json()['requests']
        assert pending[0]['id'] == key and claim not in str(pending)
        before = await client.post('/pairing/claim', json={'id': key, 'claim': claim})
        assert before.json() == {'status': 'pending'}
        assert (await client.post(f'/pairing/{key}/approve')).status_code == 200
        paired = await client.post('/pairing/claim', json={'id': key, 'claim': claim})
        assert paired.json()['token'] == 'test-device-key'
        assert paired.headers['cache-control'] == 'no-store'
        assert (await client.post('/pairing/claim', json={'id': key, 'claim': claim})).json()['status'] == 'expired'


async def test_runtime_is_private_control_only_and_reports_gateway_apply_state(store):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_control_app(store)), base_url='http://control') as client:
        runtime = (await client.get('/internal/runtime')).json()
        assert runtime['env']['API_SERVER_KEY'] == 'test-hermes-key'
        await client.put('/internal/status', json={'revision': runtime['revision'], 'online': True, 'error': None})
        status = (await client.get('/status')).json()
        assert status['gateway']['revision'] == runtime['revision'] and status['gateway']['online']
        assert 'test-hermes-key' not in str(status)
        invalid = await client.put('/internal/status', json={'revision': 1, 'online': True, 'error': 'sensitive-provider-response'})
        assert invalid.status_code == 400 and 'sensitive' not in invalid.text
        malformed = await client.put('/internal/status', json={'revision': 1, 'online': True, 'error': []})
        assert malformed.status_code == 400
