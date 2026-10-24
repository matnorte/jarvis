"""Native Hermes dashboard routes. Its existing auth gate protects this router.

Explicit owner-facing routes only: private runtime/credential/claim endpoints must
never be forwarded to a browser. The control socket is root/Hermes-group-only.
"""
import asyncio
import json
import re

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse


def create_router(transport_factory=None):
    router = APIRouter()
    if transport_factory is None:
        transport_factory = lambda: httpx.AsyncHTTPTransport(uds='/run/jarvis/control.sock')

    async def forward(method, path, body=None):
        try:
            async with httpx.AsyncClient(transport=transport_factory(), base_url='http://jarvis-control', timeout=10) as client:
                response = await client.request(method, path, json=body)
            if response.status_code >= 400:
                raise HTTPException(400, 'Jarvis could not accept this configuration. Check the fields and try again.')
            return response.json()
        except (httpx.HTTPError, ValueError):
            raise HTTPException(503, 'Jarvis control is unavailable. Check the voice gateway service.') from None

    def result(data):
        return JSONResponse(data, headers={'Cache-Control': 'no-store'})

    @router.get('/state')
    async def state():
        settings, status, pairings = await asyncio.gather(
            forward('GET', '/settings'), forward('GET', '/status'), forward('GET', '/pairing'))
        return result({**settings, 'status': status, 'pairings': pairings['requests']})

    @router.put('/settings')
    async def save(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            if len(raw) + len(chunk) > 8192:
                raise HTTPException(413, 'Request too large')
            raw.extend(chunk)
        try:
            body = json.loads(raw)
            if not isinstance(body, dict) or set(body) - {'settings', 'elevenlabs_key'}:
                raise ValueError
        except (ValueError, UnicodeError):
            raise HTTPException(400, 'Invalid Jarvis configuration') from None
        return result(await forward('PUT', '/settings', body))

    @router.post('/pairing/{key}/{action}')
    async def pairing(key: str, action: str):
        if not re.fullmatch(r'[a-f0-9]{8}', key) or action not in ('approve', 'decline'):
            raise HTTPException(404, 'Not found')
        return result(await forward('POST', f'/pairing/{key}/{action}'))

    return router


router = create_router()
