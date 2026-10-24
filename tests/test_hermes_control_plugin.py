import importlib.util
from pathlib import Path

import httpx

from server.control import create_control_app
from server.control_store import ControlStore


async def test_native_plugin_proxies_only_owner_settings_and_approval_never_runtime_or_claim(tmp_path):
    path = Path(__file__).parents[1] / 'integrations/hermes-jarvis/dashboard/plugin_api.py'
    spec = importlib.util.spec_from_file_location('jarvis_plugin_test', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    env = tmp_path / '.env'
    env.write_text('ELEVENLABS_API_KEY=hidden-test-key\nAPI_SERVER_KEY=hidden-hermes-key\nDEVICE_TOKENS=android:hidden-token\n')
    store = ControlStore(tmp_path / 'settings.json', env)
    control = httpx.ASGITransport(app=create_control_app(store))
    from fastapi import FastAPI
    app = FastAPI()
    app.include_router(module.create_router(transport_factory=lambda: control), prefix='/api/plugins/jarvis')
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://hermes') as browser:
        state = await browser.get('/api/plugins/jarvis/state')
        assert state.status_code == 200 and 'hidden' not in state.text
        assert state.headers['cache-control'] == 'no-store'
        updated = await browser.put('/api/plugins/jarvis/settings', json={'settings': {'wake_sensitivity': .7}})
        assert updated.status_code == 200 and updated.json()['settings']['wake_sensitivity'] == .7
        request = store.request_pairing('x' * 43)
        approved = await browser.post(f'/api/plugins/jarvis/pairing/{request["id"]}/approve')
        assert approved.json()['ok']
        for path in ('internal/runtime', 'runtime', 'pairing/claim'):
            assert (await browser.get('/api/plugins/jarvis/' + path)).status_code == 404
        assert (await browser.post('/api/plugins/jarvis/pairing/claim', json={'id':request['id'], 'claim':'x'*43})).status_code == 404
        assert (await browser.post(f'/api/plugins/jarvis/pairing/{request["id"]}/invented')).status_code == 404
        invalid = await browser.put('/api/plugins/jarvis/settings', json={'settings': {'wake_sensitivity': 'private-input'}})
        assert invalid.status_code == 400 and 'private-input' not in invalid.text
