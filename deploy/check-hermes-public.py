"""Fail-closed preflight before publishing Hermes. Never prints response bodies.

Run against the loopback dashboard with its intended public Host/Origin, then
against public HTTPS after proxy reload. No credentials are read or transmitted.
"""
import json
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class AccessCheckError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def check_access(address: str, public_origin: str):
    origin = urlsplit(public_origin)
    if (origin.scheme != 'https' or not origin.hostname or origin.username or
            origin.password or origin.path not in ('', '/') or origin.query or origin.fragment):
        raise AccessCheckError('A public HTTPS origin is required')
    opener = build_opener(ProxyHandler({}), NoRedirect())

    def get(path):
        request = Request(address.rstrip('/') + path, headers={
            'Host': origin.netloc, 'Origin': public_origin.rstrip('/'),
            'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': origin.netloc,
        })
        try:
            response = opener.open(request, timeout=10)
        except HTTPError as error:
            response = error
        except (URLError, OSError, ValueError):
            raise AccessCheckError('Dashboard access check could not connect') from None
        with response:
            body = response.read(65537)
            if len(body) > 65536:
                raise AccessCheckError('Dashboard access check received an oversized response')
            return response.code, response.headers, body

    code, _, raw = get('/api/status')
    try:
        status = json.loads(raw)
        providers = status.get('auth_providers')
        valid = (code == 200 and status.get('auth_required') is True and
                 isinstance(providers, list) and bool(providers) and
                 all(value in ('nous', 'self-hosted') for value in providers))
    except (ValueError, AttributeError):
        valid = False
    if not valid:
        raise AccessCheckError('Publication refused: an internet-facing OAuth gate is required')
    for path in ('/api/config', '/api/plugins/jarvis/state', '/api/auth/me'):
        if get(path)[0] not in (401, 403):
            raise AccessCheckError('Publication refused: an owner API is accessible without login')
    code, headers, _ = get('/jarvis?profile=fast')
    login = urlsplit(headers.get('Location', ''))
    if code not in (302, 303, 307, 308) or login.path not in ('/login', '/auth/login') or login.netloc not in ('', origin.netloc):
        raise AccessCheckError('Publication refused: the Jarvis page must require login')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Usage: python3 deploy/check-hermes-public.py ADDRESS PUBLIC_HTTPS_ORIGIN')
    try:
        check_access(sys.argv[1], sys.argv[2])
    except AccessCheckError as error:
        sys.exit(str(error))
    print('PASS: OAuth required; owner APIs denied; Jarvis redirects to login')
