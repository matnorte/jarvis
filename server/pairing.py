"""Two bounded public pairing relays. Owner approval stays on the private control API."""
import json
import re
import time
from collections import OrderedDict, deque

from fastapi import Request
from fastapi.responses import JSONResponse

from server.control_client import ControlUnavailable


def mount_pairing(app, control):
    buckets = OrderedDict()

    async def relay(request: Request, action):
        def response(data, code=200):
            return JSONResponse(data, status_code=code, headers={'Cache-Control': 'no-store'})
        peer = request.client.host if request.client else 'unknown'
        now = time.monotonic()
        bucket = buckets.setdefault(peer, deque())
        buckets.move_to_end(peer)
        while bucket and bucket[0] <= now - 60:
            bucket.popleft()
        if len(bucket) >= 120:
            return response({'error': 'Too many pairing attempts; try again shortly'}, 429)
        bucket.append(now)
        while len(buckets) > 256:
            buckets.popitem(last=False)
        raw = bytearray()
        async for part in request.stream():
            if len(raw) + len(part) > 1024:
                return response({'error': 'Request too large'}, 413)
            raw.extend(part)
        try:
            body = json.loads(raw)
            expected = {'claim', 'device'} if action == 'request' else {'id', 'claim'}
            required = {'claim'} if action == 'request' else expected
            if not isinstance(body, dict) or set(body) - expected or not required <= set(body) or not isinstance(body.get('claim'), str):
                raise ValueError
            if action == 'request' and (not isinstance(body.get('device', 'android'), str) or body.get('device', 'android') not in ('android', 'win')):
                raise ValueError
            if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', body['claim']):
                raise ValueError
            if action == 'claim' and (not isinstance(body['id'], str) or not re.fullmatch(r'[a-f0-9]{8}', body['id'])):
                raise ValueError
        except (ValueError, UnicodeError):
            return response({'error': 'Invalid pairing request'}, 400)
        try:
            result = await (control.pairing_request(body) if action == 'request' else control.pairing_claim(body))
        except ControlUnavailable:
            return response({'error': 'Pairing unavailable; try again shortly'}, 503)
        return response(result)

    @app.post('/pairing/request')
    async def pairing_request(request: Request):
        return await relay(request, 'request')

    @app.post('/pairing/claim')
    async def pairing_claim(request: Request):
        return await relay(request, 'claim')
