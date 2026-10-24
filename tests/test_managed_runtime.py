import asyncio
import json
from types import SimpleNamespace

from server.config import Settings
from server.managed_runtime import ManagedRuntime


class FakeControl:
    def __init__(self):
        self.data = {'revision': 1, 'env': {'ELEVENLABS_API_KEY':'k', 'ELEVENLABS_VOICE_ID':'v', 'API_SERVER_KEY':'h', 'DEVICE_TOKENS':'android:t'},
                     'phone': {'wake_sensitivity': .6, 'vision_enabled': True}}
        self.statuses = []
    async def runtime(self): return self.data
    async def status(self, **status): self.statuses.append(status)


class FakeSession:
    def __init__(self): self.busy = False; self.applied = []
    def configure(self, **deps):
        if self.busy: return False
        self.applied.append(deps); return True


def deps(name):
    return SimpleNamespace(stt=name+'-stt', router=name+'-router', tts=name+'-tts', canned=name+'-canned')


async def test_managed_preferences_offer_and_idle_ack_preserve_in_progress_turn():
    control = FakeControl()
    initial = deps('old')
    prepared = []
    async def prepare(*args): prepared.append(args); return deps('new')
    runtime = ManagedRuntime(control, Settings.from_env(control.data['env']), initial, prepare)
    await runtime.tick()
    sent = []
    async def send(message): sent.append(json.loads(message))
    session = FakeSession()
    await runtime.attach('phone', session, send)
    assert sent[-1]['type'] == 'config' and sent[-1]['revision'] == 1
    await runtime.ack('phone', 1, False)
    assert not session.applied
    await runtime.ack('phone', 1, True)
    assert session.applied[-1]['stt'] == 'old-stt'
    control.data = {**control.data, 'revision': 2, 'phone': {'wake_sensitivity': .8, 'vision_enabled': False}}
    session.busy = True
    await runtime.tick()
    assert sent[-1]['settings']['wake_sensitivity'] == .8
    await runtime.ack('phone', 2, True)
    assert len(session.applied) == 1  # server also guards an incorrect idle claim
    session.busy = False
    await runtime.tick()
    assert session.applied[-1]['vision_enabled'] is False
    assert not prepared  # phone-only changes preserve STT/TTS/router and history
    assert control.statuses[-1]['phone_revision'] == 2
    runtime.detach('phone')
    await runtime.tick()
    assert not control.statuses[-1]['phone_online']


async def test_dependency_preparation_keeps_old_pipeline_until_ready_and_phone_idle():
    control = FakeControl()
    entered, ready = asyncio.Event(), asyncio.Event()
    async def prepare(*args): entered.set(); await ready.wait(); return deps('new')
    runtime = ManagedRuntime(control, Settings.from_env(control.data['env']), deps('old'), prepare)
    await runtime.tick()
    session = FakeSession()
    async def send(_message): pass
    await runtime.attach('phone', session, send)
    await runtime.ack('phone', 1, True)
    control.data = {**control.data, 'revision': 2, 'env': {**control.data['env'], 'ELEVENLABS_VOICE_ID':'v2'}}
    task = asyncio.create_task(runtime.tick())
    await entered.wait()
    assert runtime.deps.stt == 'old-stt'
    await runtime.report()
    assert control.statuses[-1]['online'] and control.statuses[-1]['revision'] == 1
    await runtime.ack('phone', 2, False)
    ready.set(); await task
    assert len(session.applied) == 1 and runtime.deps.stt == 'new-stt'
    await runtime.ack('phone', 2, True)
    assert session.applied[-1]['stt'] == 'new-stt'


async def test_failed_apply_keeps_working_pipeline_and_does_not_retry_expensive_preparation_forever():
    control = FakeControl()
    calls = []
    async def prepare(*args): calls.append(1); raise ValueError('private-provider-response')
    runtime = ManagedRuntime(control, Settings.from_env(control.data['env']), deps('old'), prepare)
    await runtime.tick()
    control.data = {**control.data, 'revision': 2, 'env': {**control.data['env'], 'ELEVENLABS_VOICE_ID':'v2'}}
    await runtime.tick(); await runtime.tick()
    assert runtime.deps.stt == 'old-stt' and len(calls) == 1
    assert control.statuses[-1]['error'] == 'apply_failed'
    assert 'private' not in str(control.statuses)


async def test_managed_status_distinguishes_windows_from_android():
    control = FakeControl()
    runtime = ManagedRuntime(control, Settings.from_env(control.data['env']), deps('old'), None)
    await runtime.tick()
    async def send(_): pass
    await runtime.attach('pc', FakeSession(), send, device='win')
    await runtime.ack('pc', 1, True)
    await runtime.report()
    assert control.statuses[-1]['windows_online'] and control.statuses[-1]['windows_revision'] == 1
    assert not control.statuses[-1]['phone_online']
    await runtime.attach('phone', FakeSession(), send)
    await runtime.ack('phone', 1, True)
    await runtime.report()
    assert control.statuses[-1]['phone_online']
    runtime.detach('pc')
    await runtime.report()
    assert not control.statuses[-1]['windows_online'] and control.statuses[-1]['phone_online']
