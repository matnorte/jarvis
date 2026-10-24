import json
import time

import httpx

from server.control import create_control_app
from server.control_client import ControlClient
from server.control_store import ControlStore
from test_app import make, hello


def managed(tmp_path):
    env = tmp_path / '.env'
    env.write_text('ELEVENLABS_API_KEY=k\nELEVENLABS_VOICE_ID=v\nAPI_SERVER_KEY=h\nDEVICE_TOKENS=android:paired-test-token\n')
    store = ControlStore(tmp_path / 'settings.json', env)
    http = httpx.AsyncClient(transport=httpx.ASGITransport(app=create_control_app(store)), base_url='http://control')
    control = ControlClient(http=http)
    client, deps = make(tokens={'paired-test-token':'android'}, control=control, control_interval=.01)
    return client, deps, control, store


def test_public_phone_pairing_has_no_owner_or_runtime_routes_and_needs_approval(tmp_path):
    client, _, _, store = managed(tmp_path)
    with client:
        for path in ('/settings', '/internal/runtime', '/pairing', '/pairing/01234567/approve'):
            assert client.get(path).status_code == 404
        assert client.post('/pairing/01234567/approve').status_code == 404
        claim = 'x' * 43
        request = client.post('/pairing/request', json={'claim':claim}).json()
        assert 'claim' not in request
        pending = client.post('/pairing/claim', json={'id':request['id'], 'claim':claim})
        assert pending.json() == {'status':'pending'}
        store.approve_pairing(request['id'])
        paired = client.post('/pairing/claim', json={'id':request['id'], 'claim':claim})
        assert paired.json()['token'] == 'paired-test-token'
        assert paired.headers['cache-control'] == 'no-store'
        assert client.post('/pairing/request', content=b'x'*2000).status_code == 413
        invalid = client.post('/pairing/request', json={'claim':'private-input'})
        assert invalid.status_code == 400 and 'private-input' not in invalid.text


def test_new_phone_receives_config_and_applies_ack_at_idle_while_legacy_voice_is_unchanged(tmp_path):
    client, _, control, store = managed(tmp_path)
    with client:
        with client.websocket_connect('/ws') as ws:
            ws.send_text(json.dumps({'type':'hello','token':'paired-test-token','device':'android','client_config':True,'client_vad':True}))
            config = json.loads(ws.receive_text())
            assert config['type'] == 'config' and config['revision'] == 1
            ws.send_text('{"type":"config_ack","revision":1,"idle":true}')
            store.save({'wake_sensitivity':.8})
            updated = json.loads(ws.receive_text())
            assert updated['type'] == 'config' and updated['settings']['wake_sensitivity'] == .8
            ws.send_text('{"type":"config_ack","revision":2,"idle":false}')
            ws.send_text('{"type":"text","text":"hello"}')
            while True:
                message = ws.receive()
                if message.get('text') and json.loads(message['text'])['type'] == 'speaking_done': break
            ws.send_text('{"type":"config_ack","revision":2,"idle":true}')
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                status = client.portal.call(control._http.get, '/status').json()
                if status['phone']['revision'] == 2: break
                time.sleep(.01)
            assert status['phone'] == {'revision':2, 'online':True}
        with client.websocket_connect('/ws') as legacy:
            legacy.send_text(hello('paired-test-token'))
            legacy.send_bytes(b'\x01\x00' * 512)
            assert json.loads(legacy.receive_text()) == {'type':'state', 'state':'listening'}


def test_control_outage_is_retryable_not_a_permanent_token_rejection(tmp_path):
    from server.control_client import ControlUnavailable
    from test_app import closed_code
    client, _, control, _ = managed(tmp_path)
    with client:
        async def unavailable(): raise ControlUnavailable('Jarvis control unavailable')
        control.runtime = unavailable
        with client.websocket_connect('/ws') as ws:
            ws.send_text(hello('paired-test-token'))
            assert closed_code(ws) == 1013


def test_public_pairing_limits_attempts_without_echoing_private_inputs(tmp_path):
    client, _, _, _ = managed(tmp_path)
    with client:
        for _ in range(120):
            response = client.post('/pairing/request', json={'claim':'private-input'})
            assert response.status_code == 400 and 'private-input' not in response.text
        limited = client.post('/pairing/request', json={'claim':'x'*43})
        assert limited.status_code == 429


def test_windows_pairing_public_relay_returns_separate_credential_after_approval(tmp_path):
    client, _, _, store = managed(tmp_path)
    with client:
        request = client.post('/pairing/request', json={'claim': 'w' * 43, 'device': 'win'})
        assert request.status_code == 200
        key = request.json()['id']
        assert store.pending_pairings()[0]['device'] == 'win'
        store.approve_pairing(key)
        paired = client.post('/pairing/claim', json={'id': key, 'claim': 'w' * 43}).json()
        assert paired['status'] == 'paired' and paired['device'] == 'win'
        assert paired['token'] != 'paired-test-token'
        assert 'android:paired-test-token' in store.env_path.read_text()
        with client.websocket_connect('/ws') as windows:
            windows.send_text(json.dumps({'type': 'hello', 'token': paired['token'], 'device': 'win', 'client_config': True}))
            assert json.loads(windows.receive_text())['type'] == 'config'
            with client.websocket_connect('/ws') as phone:
                phone.send_text(json.dumps({'type': 'hello', 'token': 'paired-test-token', 'device': 'android', 'client_config': True}))
                assert json.loads(phone.receive_text())['type'] == 'config'
                windows.send_text('{"type":"config_ack","revision":1,"idle":true}')
                # Phone connection must not evict the separately authenticated Windows client.
                windows.send_bytes(b'\x01\x00' * 512)
                assert json.loads(windows.receive_text()) == {'type': 'state', 'state': 'listening'}
