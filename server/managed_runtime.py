"""Prepare server settings without disturbing a turn; apply to managed phones at idle."""
from dataclasses import dataclass, replace

from server.config import Settings
from server.protocol import config


@dataclass
class _Binding:
    session: object
    send: object
    device: str = 'android'
    ack_revision: int = 0
    idle: bool = False
    sent_revision: int = 0
    resource_revision: int = 0


class ManagedRuntime:
    def __init__(self, client, settings, deps, prepare):
        self.client, self.settings, self.deps, self.prepare = client, settings, deps, prepare
        self.revision = 0
        self.error = None
        self._failed_revision = None
        self._target = None
        self._bindings = {}

    @property
    def phone(self):
        return self._target['phone'] if self._target else {}

    async def attach(self, key, session, send, *, device='android'):
        binding = _Binding(session, send, device)
        self._bindings[key] = binding
        await self._offer(binding)

    def detach(self, key):
        self._bindings.pop(key, None)

    async def ack(self, key, revision, idle):
        binding = self._bindings.get(key)
        if binding is not None:
            binding.ack_revision, binding.idle = revision, idle
            self._apply_ready(binding)

    def active(self, key):
        binding = self._bindings.get(key)
        if binding is not None:
            binding.idle = False

    async def _offer(self, binding):
        if self._target and binding.sent_revision != self._target['revision']:
            await binding.send(config(self._target['revision'], self.phone))
            binding.sent_revision = self._target['revision']

    def _apply_ready(self, binding):
        if (not self._target or self.revision != self._target['revision'] or not binding.idle
                or binding.ack_revision != self.revision or binding.resource_revision == self.revision):
            return
        if binding.session.configure(stt=self.deps.stt, router=self.deps.router, tts=self.deps.tts,
                                     canned=self.deps.canned, vision_enabled=self.phone.get('vision_enabled', True)):
            binding.resource_revision = self.revision

    async def tick(self):
        self._target = await self.client.runtime()
        for binding in list(self._bindings.values()):
            await self._offer(binding)
        revision = self._target['revision']
        if revision != self.revision and revision != self._failed_revision:
            try:
                settings = replace(Settings.from_env(self._target['env']), canned_dir=self.settings.canned_dir)
                prepared = self.deps if settings == self.settings else await self.prepare(settings, self.settings, self.deps)
                self.settings, self.deps, self.revision = settings, prepared, revision
                self.error = None
            except Exception:
                # Never log a provider exception/body or repeatedly retry an expensive model load.
                self.error, self._failed_revision = 'apply_failed', revision
        for binding in list(self._bindings.values()):
            self._apply_ready(binding)
        await self.report()

    async def report(self):
        phones = [b for b in self._bindings.values() if b.device == 'android']
        desktops = [b for b in self._bindings.values() if b.device == 'win']
        await self.client.status(revision=self.revision, online=True, error=self.error,
                                 phone_revision=min((b.resource_revision for b in phones), default=0), phone_online=bool(phones),
                                 windows_revision=min((b.resource_revision for b in desktops), default=0), windows_online=bool(desktops))
