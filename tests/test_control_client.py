import httpx
import pytest

from server.control import create_control_app
from server.control_client import ControlClient, ControlUnavailable
from server.control_store import ControlStore


async def test_gateway_client_reads_private_runtime_and_posts_only_fixed_status(tmp_path):
    env = tmp_path / '.env'
    env.write_text('ELEVENLABS_API_KEY=test-key\nAPI_SERVER_KEY=test-hermes\nDEVICE_TOKENS=android:test-device\n')
    store = ControlStore(tmp_path / 'settings.json', env)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_control_app(store)), base_url='http://control') as http:
        client = ControlClient(http=http)
        runtime = await client.runtime()
        assert runtime['env']['API_SERVER_KEY'] == 'test-hermes'
        await client.status(revision=1, online=True, phone_revision=1, phone_online=True)
        status = (await http.get('/status')).json()
        assert status['phone'] == {'revision': 1, 'online': True}
        request = await client.pairing_request({'claim': 'x' * 43})
        store.approve_pairing(request['id'])
        result = await client.pairing_claim({'id': request['id'], 'claim': 'x' * 43})
        assert result['token'] == 'test-device'


async def test_control_transport_failure_has_no_credential_or_body_in_exception():
    def broken(request):
        raise httpx.ConnectError('untrusted-diagnostic-secret', request=request)
    async with httpx.AsyncClient(transport=httpx.MockTransport(broken), base_url='http://control') as http:
        client = ControlClient(http=http)
        with pytest.raises(ControlUnavailable) as error:
            await client.pairing_claim({'id': '01234567', 'claim': 'private-claim-value'})
        assert str(error.value) == 'Jarvis control unavailable'
