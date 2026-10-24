"""Private Unix-socket API. Never mount this app on the public voice listener.

The socket directory is accessible only to root and the Hermes service group.
Hermes's authenticated dashboard plugin exposes an explicit, redacted route subset.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from server.control_store import ControlError, ControlStore

MAX_BODY = 8192


class _BodyTooLarge(Exception):
    pass


async def _body(request: Request) -> dict:
    parts = bytearray()
    async for part in request.stream():
        if len(parts) + len(part) > MAX_BODY:
            raise _BodyTooLarge
        parts.extend(part)
    try:
        value = json.loads(parts)
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (ValueError, UnicodeError):
        raise ControlError('Invalid request') from None


def create_control_app(store: ControlStore | None = None) -> FastAPI:
    if store is None:
        store = ControlStore(Path(os.environ.get('JARVIS_CONTROL_STATE', '/app/cache/control/settings.json')),
                             Path(os.environ.get('JARVIS_ENV_FILE', '/app/deploy-config/.env')))
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    gateway = {'revision': 0, 'online': False, 'error': None}
    phone = {'revision': 0, 'online': False}
    heartbeat = 0.0

    @app.middleware('http')
    async def no_store(request, call_next):
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        return response

    @app.exception_handler(ControlError)
    async def safe_error(_request, error):
        return JSONResponse({'error': str(error)}, status_code=400)

    @app.exception_handler(_BodyTooLarge)
    async def too_large(_request, _error):
        return JSONResponse({'error': 'Request too large'}, status_code=413)

    @app.get('/settings')
    def settings():
        return store.snapshot()

    @app.put('/settings')
    async def save(request: Request):
        body = await _body(request)
        if set(body) - {'settings', 'elevenlabs_key'} or not isinstance(body.get('settings'), dict):
            raise ControlError('Invalid Jarvis configuration')
        import asyncio
        return await asyncio.to_thread(store.save, body['settings'], elevenlabs_key=body.get('elevenlabs_key'))

    @app.get('/pairing')
    def pairings():
        return {'requests': store.pending_pairings()}

    @app.post('/pairing/request')
    async def pairing_request(request: Request):
        body = await _body(request)
        if set(body) != {'claim'}:
            raise ControlError('Invalid pairing request')
        return store.request_pairing(body['claim'])

    @app.post('/pairing/claim')
    async def pairing_claim(request: Request):
        body = await _body(request)
        if set(body) != {'id', 'claim'} or not isinstance(body['id'], str) or len(body['id']) != 8:
            raise ControlError('Invalid pairing request')
        import asyncio
        return await asyncio.to_thread(store.claim_pairing, body['id'], body['claim'])

    @app.post('/pairing/{key}/approve')
    def approve(key: str):
        return {'ok': store.approve_pairing(key)}

    @app.post('/pairing/{key}/decline')
    def decline(key: str):
        return {'ok': store.decline_pairing(key)}

    @app.get('/internal/runtime')
    def runtime():
        return store.runtime()

    @app.get('/status')
    def status():
        recent = time.monotonic() - heartbeat < 30
        return {'revision': store.snapshot()['revision'],
                'gateway': {**gateway, 'online': gateway['online'] and recent},
                'phone': {**phone, 'online': phone['online'] and recent}}

    @app.put('/internal/status')
    async def update_status(request: Request):
        nonlocal heartbeat
        body = await _body(request)
        valid = (not set(body) - {'revision', 'online', 'error', 'phone_revision', 'phone_online'}
                 and type(body.get('revision')) is int and body['revision'] >= 0
                 and type(body.get('online')) is bool
                 and body.get('error') in (None, 'apply_failed', 'provider_unavailable')
                 and type(body.get('phone_revision', 0)) is int
                 and type(body.get('phone_online', False)) is bool)
        if not valid:
            raise ControlError('Invalid gateway status')
        gateway.update(revision=body['revision'], online=body['online'], error=body.get('error'))
        phone.update(revision=body.get('phone_revision', 0), online=body.get('phone_online', False))
        heartbeat = time.monotonic()
        return {'ok': True}

    return app
