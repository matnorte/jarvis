"""Server-owned configuration and short-lived, explicitly approved phone pairing.

Only nonsecret preferences are persisted in JSON. Long-lived credentials stay in
the existing server .env; claim capabilities are hashed and kept in bounded RAM.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import tempfile
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import dotenv_values, set_key
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from server.config import _parse_device_tokens


class ControlError(Exception):
    """Only fixed, user-safe messages cross the control API boundary."""


class Preferences(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    public_url: str = 'wss://jarvis.openclawchip.tech/ws'
    stt_model: str = 'base'
    tts_model: str = 'eleven_turbo_v2_5'
    voice_id: str = Field(default='', pattern=r'^[A-Za-z0-9_-]{0,100}$')
    fast_profile: str = Field(default='fast', pattern=r'^[a-z0-9][a-z0-9_-]{0,63}$')
    deep_profile: str = Field(default='deep', pattern=r'^[a-z0-9][a-z0-9_-]{0,63}$')
    wake_sensitivity: float = Field(default=.6, ge=0, le=1)
    end_silence_ms: int = Field(default=700, ge=100, le=5000)
    interrupt_by_speech: bool = False
    start_on_boot: bool = False
    vision_enabled: bool = True
    front_camera: bool = False

    @field_validator('public_url')
    @classmethod
    def valid_url(cls, value):
        if any(character.isspace() for character in value):
            raise ValueError('Invalid gateway URL')
        parsed = urlsplit(value)
        _ = parsed.port  # Validate a supplied port before synchronizing it to phones.
        if parsed.scheme != 'wss' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('Invalid gateway URL')
        return value

    @field_validator('stt_model')
    @classmethod
    def valid_stt(cls, value):
        if value not in {'tiny', 'base', 'small', 'medium', 'large-v3', 'turbo'}:
            raise ValueError('Invalid recognition model')
        return value

    @field_validator('tts_model')
    @classmethod
    def valid_tts(cls, value):
        if value not in {'eleven_turbo_v2_5', 'eleven_flash_v2_5', 'eleven_multilingual_v2'}:
            raise ValueError('Invalid voice model')
        return value


@dataclass
class _Pairing:
    id: str
    deadline: float
    proof_hash: bytes = field(repr=False)
    approved: bool = False


class ControlStore:
    PAIRING_TTL = 180
    MAX_PAIRINGS = 16

    def __init__(self, path: Path, env_path: Path, clock=time.monotonic):
        self.path, self.env_path, self.clock = Path(path), Path(env_path), clock
        self._lock = threading.RLock()
        self._pairings: dict[str, _Pairing] = {}
        env = self._env()
        self._preferences = Preferences(
            stt_model=env.get('STT_MODEL') or 'base',
            tts_model=env.get('TTS_MODEL') or 'eleven_turbo_v2_5',
            voice_id=env.get('ELEVENLABS_VOICE_ID') or '',
        )
        self._revision = 1
        if self.path.exists():
            saved = json.loads(self.path.read_text(encoding='utf-8'))
            self._preferences = Preferences.model_validate(saved['settings'])
            self._revision = int(saved['revision'])

    def _env(self):
        return dict(dotenv_values(self.env_path, interpolate=False))

    def snapshot(self):
        with self._lock:
            env = self._env()
            return {'revision': self._revision, 'settings': self._preferences.model_dump(), 'secrets': {
                'elevenlabs': bool(env.get('ELEVENLABS_API_KEY')),
                'hermes': bool(env.get('API_SERVER_KEY')),
                'android': any(name == 'android' for name in _parse_device_tokens(env.get('DEVICE_TOKENS') or '').values()),
            }}

    def save(self, patch: dict, *, elevenlabs_key: str | None = None):
        with self._lock:
            try:
                updated = Preferences.model_validate({**self._preferences.model_dump(), **patch})
                if elevenlabs_key is not None and not re.fullmatch(r'[A-Za-z0-9_.-]{1,512}', elevenlabs_key):
                    raise ValueError('Invalid secret')
            except (ValueError, TypeError, ValidationError):
                raise ControlError('Invalid Jarvis configuration') from None
            self._write_preferences(updated, self._revision + 1)
            if elevenlabs_key is not None:
                set_key(self.env_path, 'ELEVENLABS_API_KEY', elevenlabs_key, quote_mode='never')
            self._preferences = updated
            self._revision += 1
            return self.snapshot()

    def _write_preferences(self, preferences, revision):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='.settings-', dir=self.path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump({'revision': revision, 'settings': preferences.model_dump()}, stream)
                stream.flush(); os.fsync(stream.fileno())
            os.replace(name, self.path)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    def runtime(self):
        with self._lock:
            env = self._env()
            result = {key: env.get(key) or '' for key in ('ELEVENLABS_API_KEY', 'API_SERVER_KEY', 'DEVICE_TOKENS')}
            p = self._preferences
            result.update(ELEVENLABS_VOICE_ID=p.voice_id, TTS_MODEL=p.tts_model, STT_MODEL=p.stt_model,
                          HERMES_FAST_URL=f'http://127.0.0.1:8642/p/{p.fast_profile}',
                          HERMES_DEEP_URL=f'http://127.0.0.1:8642/p/{p.deep_profile}')
            phone = {key: getattr(p, key) for key in ('wake_sensitivity', 'end_silence_ms', 'interrupt_by_speech',
                                                     'start_on_boot', 'vision_enabled', 'front_camera')}
            phone['server_url'] = p.public_url
            return {'revision': self._revision, 'env': result, 'phone': phone}

    @staticmethod
    def _proof(claim):
        if not isinstance(claim, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', claim):
            raise ControlError('Invalid pairing request')
        return hashlib.sha256(claim.encode('ascii')).digest()

    def _prune(self):
        for key in [key for key, item in self._pairings.items() if item.deadline <= self.clock()]:
            del self._pairings[key]

    def request_pairing(self, claim):
        proof = self._proof(claim)
        with self._lock:
            self._prune()
            item = next((p for p in self._pairings.values() if secrets.compare_digest(p.proof_hash, proof)), None)
            if item is None:
                if len(self._pairings) >= self.MAX_PAIRINGS:
                    raise ControlError('Too many pairing requests; try again shortly')
                key = secrets.token_hex(4)
                while key in self._pairings:
                    key = secrets.token_hex(4)
                item = _Pairing(key, self.clock() + self.PAIRING_TTL, proof)
                self._pairings[key] = item
            return {'id': item.id, 'expires_in': max(0, int(item.deadline - self.clock()))}

    def pending_pairings(self):
        with self._lock:
            self._prune()
            return [{'id': p.id, 'expires_in': max(0, int(p.deadline - self.clock())), 'approved': p.approved}
                    for p in self._pairings.values()]

    def approve_pairing(self, key):
        with self._lock:
            self._prune()
            item = self._pairings.get(key)
            if item is None:
                return False
            item.approved = True
            return True

    def decline_pairing(self, key):
        with self._lock:
            return self._pairings.pop(key, None) is not None

    def claim_pairing(self, key, claim):
        proof = self._proof(claim)
        with self._lock:
            self._prune()
            item = self._pairings.get(key)
            if item is None or not secrets.compare_digest(item.proof_hash, proof):
                return {'status': 'expired'}
            if not item.approved:
                return {'status': 'pending'}
            env = self._env()
            tokens = _parse_device_tokens(env.get('DEVICE_TOKENS') or '')
            token = next((token for token, device in tokens.items() if device == 'android'), None)
            if token is None:
                token = secrets.token_urlsafe(32)
                tokens[token] = 'android'
                set_key(self.env_path, 'DEVICE_TOKENS', ','.join(f'{device}:{token}' for token, device in tokens.items()), quote_mode='never')
                self._revision += 1
                self._write_preferences(self._preferences, self._revision)
            del self._pairings[key]
            return {'status': 'paired', 'token': token, 'server_url': self._preferences.public_url,
                    'revision': self._revision, 'settings': self.runtime()['phone']}
