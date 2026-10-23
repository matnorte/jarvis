import json

import pytest

from server.protocol import EndUtterance, Hello, Interrupt, TextInput, ProtocolError, parse_client, state, transcript


def test_parse_hello():
    assert parse_client('{"type":"hello","token":"t","device":"win"}') == Hello("t", "win")


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
