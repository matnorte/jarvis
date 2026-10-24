import test from 'node:test';
import assert from 'node:assert/strict';
import {preferences, parseServer, hello, DEFAULTS} from '../src/core/protocol.mjs';

test('Windows hello opts into client VAD/config with a separate device type', () => {
  assert.deepEqual(hello('fake-test-token'), {type:'hello', token:'fake-test-token', device:'win', client_vad:true, client_config:true});
});
test('canonical settings reject unsafe addresses/coercion and retain only public preferences', () => {
  for (const patch of [{server_url:'ws://insecure.test/ws'}, {server_url:'wss://user:password@example.test/ws'},
    {server_url:'wss://example.test/ws?token=private'}, {server_url:'wss://example.test/ws\n'}, {end_silence_ms:true},
    {wake_sensitivity:NaN}, {interrupt_by_speech:'true'}, {front_camera:1}]) {
    assert.throws(() => preferences({...DEFAULTS,...patch}), /Invalid/);
  }
  assert.equal(preferences({...DEFAULTS,token:'do-not-retain'}).token, undefined);
});
test('parsing is bounded, strict, and unknown/malformed messages have no effects', () => {
  for (const raw of ['[]','null','broken','{"type":"config","revision":true}', 'x'.repeat(70000)]) assert.equal(parseServer(raw), null);
  assert.deepEqual(parseServer('{"type":"transcript","text":"Olá","final":true}'), {type:'transcript',text:'Olá',final:true});
  assert.equal(parseServer('{"type":"state","state":"invented"}'),null);
  assert.equal(parseServer('{"type":"transcript","text":"x","final":"true"}'),null);
  assert.equal(parseServer(JSON.stringify({type:'config',revision:2,settings:DEFAULTS})).revision,2);
});
