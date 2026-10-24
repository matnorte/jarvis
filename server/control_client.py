"""Gateway-to-control transport over the private Unix socket. No public admin API."""
import httpx


class ControlUnavailable(Exception):
    pass


class ControlClient:
    def __init__(self, socket='/run/jarvis/control.sock', http: httpx.AsyncClient | None = None):
        self._owned = http is None
        self._http = http or httpx.AsyncClient(transport=httpx.AsyncHTTPTransport(uds=str(socket)),
                                               base_url='http://jarvis-control', timeout=5)

    async def _request(self, method, path, body=None):
        try:
            response = await self._http.request(method, path, json=body)
            response.raise_for_status()
            value = response.json()
            if not isinstance(value, dict):
                raise ValueError
            return value
        except (httpx.HTTPError, ValueError):
            raise ControlUnavailable('Jarvis control unavailable') from None

    async def runtime(self):
        return await self._request('GET', '/internal/runtime')

    async def status(self, *, revision, online, phone_revision=0, phone_online=False, error=None):
        return await self._request('PUT', '/internal/status', {
            'revision': revision, 'online': online, 'error': error,
            'phone_revision': phone_revision, 'phone_online': phone_online,
        })

    async def pairing_request(self, body):
        return await self._request('POST', '/pairing/request', body)

    async def pairing_claim(self, body):
        return await self._request('POST', '/pairing/claim', body)

    async def close(self):
        if self._owned:
            await self._http.aclose()
