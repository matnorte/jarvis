import test from 'node:test';
import assert from 'node:assert/strict';
import {Conversation} from '../src/core/conversation.mjs';

function setup(config = {}) {
  let time = 0; const events = [];
  const fx = Object.fromEntries(['send', 'sendAudio', 'play', 'flush', 'chime', 'errorTone', 'reconnect'].map(key => [key, value => events.push([key, value])]));
  const c = new Conversation(fx, config, () => time);
  const frame = (speech = false, wake = false, ms = 32) => { time += ms; c.frame(new Int16Array(512).fill(speech ? 2000 : 0), wake, speech); };
  const thinking = () => { c.connected(); c.tap(); frame(true); frame(false, false, 701); assert.equal(c.state.phase, 'THINKING'); };
  return {c, events, frame, thinking, advance: ms => time += ms};
}

test('pre-roll is bounded, chronological, copied and sent only after wake', () => {
  const {c, events} = setup(); c.connected();
  const frame = new Int16Array(512);
  for (let i = 0; i < 15; i++) { frame.fill(i); c.frame(frame, false, false); }
  assert.equal(events.length, 0); c.tap();
  const audio = events.filter(e => e[0] === 'sendAudio').map(e => new DataView(e[1].buffer, e[1].byteOffset).getInt16(0, true));
  assert.deepEqual(audio, Array.from({length: 10}, (_, i) => i + 5));
});
test('six seconds without speech cancels instead of transcribing', () => {
  const {c, events, frame} = setup(); c.connected(); c.tap(); frame(false, false, 6001);
  assert.equal(c.state.phase, 'WAITING');
  assert.deepEqual(events.filter(e => e[0] === 'send'), [['send', {type: 'interrupt'}]]);
});
test('speech plus configured silence ends utterance and keeps previous transcript until a new final', () => {
  const {c, events, frame} = setup({endSilenceMs: 900}); c.connected(); c.tap();
  c.server({type:'transcript', text:'old', final:true}); frame(true); frame(false, false, 800);
  assert.equal(c.state.phase, 'LISTENING'); frame(false, false, 101);
  assert.equal(c.state.phase, 'THINKING'); assert.ok(events.some(e => e[1]?.type === 'end_utterance'));
  c.server({type:'reply_text', delta:'answer'}); c.audio(new Uint8Array(4800));
  c.tap(); assert.equal(c.state.transcript, 'old'); assert.equal(c.state.reply, '');
});
for (const order of ['server-first', 'drain-first']) test(`follow-up waits for server AND local playback (${order})`, () => {
  const {c, thinking, frame} = setup(); thinking(); c.audio(new Uint8Array(4800));
  if (order === 'server-first') { c.server({type:'speaking_done'}); assert.equal(c.state.phase, 'SPEAKING'); c.playbackDone(); }
  else { c.playbackDone(); assert.equal(c.state.phase, 'SPEAKING'); c.server({type:'speaking_done'}); }
  assert.equal(c.state.phase, 'FOLLOW_UP'); frame(false, false, 6001); assert.equal(c.state.phase, 'WAITING');
});
test('interrupt flush precedes audio and stale replies are ignored until thinking', () => {
  const {c, events, thinking, frame} = setup(); thinking(); c.audio(new Uint8Array(4800)); events.length = 0;
  c.tap(); assert.equal(events[0][1].type, 'interrupt'); assert.equal(events[1][0], 'flush');
  c.audio(new Uint8Array(48)); c.server({type:'reply_text', delta:'stale'}); c.server({type:'speaking_done'});
  assert.equal(c.state.phase, 'LISTENING'); assert.equal(c.state.reply, '');
  frame(true); frame(false, false, 701); c.server({type:'state', state:'thinking'}); c.audio(new Uint8Array(48));
  assert.equal(c.state.phase, 'SPEAKING');
});
test('speech barge-in needs ten consecutive VAD frames', () => {
  const {c, thinking, frame} = setup({interruptBySpeech:true}); thinking(); c.audio(new Uint8Array(4800));
  for (let i=0;i<9;i++) frame(true); assert.equal(c.state.phase, 'SPEAKING');
  frame(false); for (let i=0;i<9;i++) frame(true); assert.equal(c.state.phase, 'SPEAKING');
  frame(true); assert.equal(c.state.phase, 'LISTENING');
});
test('pause emits no further effects; resume discards old pre-roll', () => {
  const {c, events, thinking, frame} = setup(); thinking(); c.audio(new Uint8Array(48)); c.pause(); events.length=0;
  frame(true, true); c.tap(); c.server({type:'speaking_done'}); c.audio(new Uint8Array(48)); c.playbackDone();
  assert.equal(events.length, 0); assert.equal(c.state.phase, 'PAUSED'); c.resume(); c.tap();
  assert.equal(events.filter(e => e[0] === 'sendAudio').length, 0);
});
test('offline wake waits for connection; bad token stops retries; replacement is actionable', () => {
  const {c, events} = setup(); c.disconnected(); c.tap(); c.tap();
  assert.equal(events.filter(e => e[0] === 'reconnect').length,1); assert.equal(c.state.phase,'OFFLINE');
  c.connected(); assert.equal(c.state.phase,'LISTENING'); c.rejected(4401); events.length=0; c.tap();
  assert.equal(events.length,0); assert.match(c.state.notice,/Pair/);
  c.connected(); c.rejected(4409); assert.match(c.state.notice,/another/i);
});
test('safe configuration preserves captions and cannot mutate a live turn', () => {
  const {c, thinking, frame} = setup(); thinking(); c.server({type:'transcript', text:'retained', final:true});
  assert.equal(c.configure({endSilenceMs:500}),false); c.audio(new Uint8Array(48)); c.playbackDone();
  c.server({type:'speaking_done'}); assert.equal(c.configure({endSilenceMs:500}),false);
  frame(false,false,6001); assert.equal(c.configure({endSilenceMs:500}),true); assert.equal(c.state.transcript,'retained');
});
test('late thinking after server done does not prevent drain-based follow-up', () => {
  const {c, thinking} = setup(); thinking(); c.audio(new Uint8Array(48)); c.server({type:'speaking_done'});
  c.server({type:'state',state:'thinking'}); c.playbackDone(); assert.equal(c.state.phase,'FOLLOW_UP');
});
test('30 second speech cap; follow-up speech resumes without wake', () => {
  const {c, frame} = setup(); c.connected(); c.tap(); frame(true,false,30001);
  assert.equal(c.state.phase,'THINKING'); c.audio(new Uint8Array(48)); c.playbackDone(); c.server({type:'speaking_done'});
  frame(true); assert.equal(c.state.phase,'LISTENING');
});
