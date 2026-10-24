import json

import pytest

from server.protocol import EndUtterance, Hello, Interrupt, TextInput, ProtocolError, parse_client, state, transcript


def test_parse_hello():
    assert parse_client('{"type":"hello","token":"t","device":"win"}') == Hello("t", "win")


def test_camera_protocol_is_explicit_bounded_and_does_not_repr_image_data():
    from server.protocol import Camera, CameraFrame
    assert parse_client('{"type":"camera","enabled":true}') == Camera(True)
    assert parse_client('{"type":"camera","enabled":false}') == Camera(False)
    message = parse_client('{"type":"camera_frame","jpeg":"fake-image-content"}')
    assert isinstance(message, CameraFrame) and message.jpeg == "fake-image-content"
    assert "fake-image-content" not in repr(message)
    for invalid in ('1', '"true"', 'null'):
        with pytest.raises(ProtocolError):
            parse_client('{"type":"camera","enabled":%s}' % invalid)
    with pytest.raises(ProtocolError):
        parse_client(json.dumps({"type": "camera_frame", "jpeg": "x" * 180000}))


def test_hello_client_vad_is_optional_and_strict_boolean():
    assert parse_client('{"type":"hello","token":"t","device":"win"}').client_vad is False
    assert parse_client('{"type":"hello","token":"t","device":"android","client_vad":true}').client_vad is True
    assert parse_client('{"type":"hello","token":"t","device":"android","client_vad":false}').client_vad is False
    for invalid in ('1', '"true"', 'null', '[]'):
        with pytest.raises(ProtocolError):
            parse_client('{"type":"hello","token":"t","device":"android","client_vad":%s}' % invalid)


def test_parse_other_client_messages():
    assert parse_client('{"type":"text","text":"hi"}') == TextInput("hi")
    assert parse_client('{"type":"end_utterance"}') == EndUtterance()
    assert parse_client('{"type":"interrupt"}') == Interrupt()


def test_parse_rejects_unknown_and_garbage():
    for bad in ['{"type":"nope"}', 'not json', '{"type":"hello"}']:
        with pytest.raises(ProtocolError):
            parse_client(bad)


def test_server_messages_are_json():
    assert json.loads(state("thinking")) == {"type": "state", "state": "thinking"}
    assert json.loads(transcript("oi", "pt", True)) == {"type": "transcript", "text": "oi", "lang": "pt", "final": True}


def test_text_lang_optional_pt_or_en():
    assert parse_client('{"type":"text","text":"oi"}') == TextInput("oi", "en")
    assert parse_client('{"type":"text","text":"oi","lang":"pt"}') == TextInput("oi", "pt")
    assert parse_client('{"type":"text","text":"hi","lang":"en"}') == TextInput("hi", "en")
    for bad in ['"es"', '"PT"', '""', "null", "1"]:
        with pytest.raises(ProtocolError):
            parse_client('{"type":"text","text":"oi","lang":%s}' % bad)
