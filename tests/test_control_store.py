import json

import pytest

from server.control_store import ControlError, ControlStore


@pytest.fixture
def store(tmp_path):
    env = tmp_path / '.env'
    env.write_text('ELEVENLABS_API_KEY=old-test-key\nELEVENLABS_VOICE_ID=voice-test\nAPI_SERVER_KEY=hermes-test-key\nDEVICE_TOKENS=win:win-test,android:android-test\nUNRELATED=keep-me\n')
    return ControlStore(tmp_path / 'settings.json', env, clock=lambda: 100.0)


def test_settings_survive_restart_and_revisions_change_only_on_valid_save(store):
    before = store.snapshot()
    saved = store.save({'wake_sensitivity': .8, 'end_silence_ms': 900})
    assert saved['revision'] == before['revision'] + 1
    assert saved['settings']['wake_sensitivity'] == .8
    assert ControlStore(store.path, store.env_path).snapshot() == saved
    with pytest.raises(ControlError):
        store.save({'end_silence_ms': 1})
    assert store.snapshot() == saved


@pytest.mark.parametrize('patch', [
    {'wake_sensitivity': 1.1}, {'end_silence_ms': True}, {'interrupt_by_speech': 'yes'},
    {'public_url': 'http://example.com'}, {'public_url': 'wss://example.com/ws?token=private'},
    {'public_url': 'wss://example.com:invalid/ws'},
    {'public_url': 'wss://example.com/ws\n'},
    {'fast_profile': '../elsewhere'}, {'unexpected': 'private-input'}, {'stt_model': 'made-up'},
])
def test_invalid_settings_never_echo_input(store, patch):
    with pytest.raises(ControlError) as error:
        store.save(patch)
    assert str(error.value) == 'Invalid Jarvis configuration'


def test_secret_replacement_stays_in_env_preserves_other_settings_and_is_masked(store):
    result = store.save({'tts_model': 'eleven_multilingual_v2'}, elevenlabs_key='new-test-key')
    assert result['secrets'] == {'elevenlabs': True, 'hermes': True, 'android': True}
    assert 'new-test-key' not in json.dumps(result)
    assert 'new-test-key' not in store.path.read_text()
    content = store.env_path.read_text()
    assert 'ELEVENLABS_API_KEY=new-test-key' in content
    assert 'API_SERVER_KEY=hermes-test-key' in content and 'UNRELATED=keep-me' in content
    assert store.runtime()['env']['ELEVENLABS_API_KEY'] == 'new-test-key'
    before = store.snapshot()
    with pytest.raises(ControlError) as error:
        store.save({}, elevenlabs_key='private\nINJECTED=bad')
    assert 'private' not in str(error.value)
    assert store.snapshot() == before


def test_pairing_requires_matching_private_claim_and_explicit_dashboard_approval(store):
    claim = 'test-private-claim-' + 'x' * 32
    request = store.request_pairing(claim)
    assert 'claim' not in json.dumps(request) and claim not in repr(store)
    assert store.request_pairing(claim) == request  # retry does not allocate another request
    assert store.claim_pairing(request['id'], claim) == {'status': 'pending'}
    assert store.pending_pairings()[0]['id'] == request['id']
    assert store.approve_pairing(request['id'])
    assert store.claim_pairing(request['id'], 'wrong-private-claim-' + 'y' * 32) == {'status': 'expired'}
    paired = store.claim_pairing(request['id'], claim)
    assert paired['status'] == 'paired' and paired['token'] == 'android-test'
    assert store.claim_pairing(request['id'], claim) == {'status': 'expired'}
    assert store.pending_pairings() == []


def test_expired_or_declined_pairings_cannot_be_approved_or_claimed(store):
    time = [0.0]
    store.clock = lambda: time[0]
    request = store.request_pairing('x' * 43)
    time[0] = 181
    assert not store.approve_pairing(request['id'])
    assert store.claim_pairing(request['id'], 'x' * 43) == {'status': 'expired'}
    fresh = store.request_pairing('y' * 43)
    assert store.decline_pairing(fresh['id'])
    assert store.claim_pairing(fresh['id'], 'y' * 43) == {'status': 'expired'}


def test_pending_pairing_memory_is_bounded_without_evicting_legitimate_requests(store):
    first = store.request_pairing('a' * 43)
    for i in range(1, 16):
        store.request_pairing(f'{i:043d}')
    with pytest.raises(ControlError, match='Too many pairing requests'):
        store.request_pairing('b' * 43)
    assert len(store.pending_pairings()) == 16
    assert store.approve_pairing(first['id'])
    assert store.claim_pairing(first['id'], 'a' * 43)['status'] == 'paired'


def test_runtime_profile_and_voice_preferences_are_canonical_not_environment_defaults(store):
    store.save({'fast_profile': 'fast', 'deep_profile': 'deep', 'vision_enabled': False, 'voice_id': 'new-voice'})
    runtime = store.runtime()
    assert runtime['env']['HERMES_FAST_URL'] == 'http://127.0.0.1:8642/p/fast'
    assert runtime['env']['ELEVENLABS_VOICE_ID'] == 'new-voice'
    assert runtime['phone']['vision_enabled'] is False
    assert 'token' not in runtime['phone']


def test_windows_pairing_is_bound_to_device_and_preserves_android_credential(store):
    claim = 'w' * 43
    before = store.env_path.read_text()
    request = store.request_pairing(claim, device='win')
    assert store.pending_pairings()[0]['device'] == 'win'
    with pytest.raises(ControlError, match='Invalid pairing request'):
        store.request_pairing(claim, device='android')
    assert store.approve_pairing(request['id'])
    paired = store.claim_pairing(request['id'], claim)
    assert paired['token'] == 'win-test' and paired['device'] == 'win'
    assert store.env_path.read_text() == before
    legacy = store.request_pairing('a' * 43)
    store.approve_pairing(legacy['id'])
    assert store.claim_pairing(legacy['id'], 'a' * 43)['token'] == 'android-test'


@pytest.mark.parametrize('device', ['root', '../win', None, True, ['win']])
def test_pairing_device_type_is_strict_and_never_echoes_input(store, device):
    with pytest.raises(ControlError, match='^Invalid pairing request$'):
        store.request_pairing('z' * 43, device=device)
