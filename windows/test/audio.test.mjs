import test from 'node:test';
import assert from 'node:assert/strict';
import {Resampler, PlaybackQueue, FrameInbox} from '../src/core/audio.mjs';

test('capture resamples actual 48k device audio into exact PCM16 512 sample frames across chunk boundaries', () => {
  const r=new Resampler(48000), frames=[];
  for(let offset=0;offset<48000;offset+=128) frames.push(...r.push(new Float32Array(Math.min(128,48000-offset)).fill(.25)));
  assert.equal(frames.length,31); assert.ok(frames.every(f=>f.length===512));
  assert.ok(Math.abs(frames[10][200]-8192)<2);
  assert.ok(r.bufferedSamples < 512);
});
test('44.1k continuous resampling does not drift, upsampling works, NaN/clipping are contained', () => {
  for (const rate of [44100,16000,8000]) {
    const r=new Resampler(rate); let count=0;
    for(let i=0;i<rate;i+=100) count+=r.push(new Float32Array(Math.min(100,rate-i)).fill(.1)).length;
    assert.equal(count,31);
  }
  const frames=new Resampler(16000).push(new Float32Array(1024).fill(Infinity));
  assert.ok(frames.every(f=>f.every(v=>v===0)));
});
test('anti-alias filter suppresses tones above the output Nyquist frequency', () => {
  function rms(frequency) {
    const r=new Resampler(48000); const input=Float32Array.from({length:48000},(_,i)=>Math.sin(2*Math.PI*frequency*i/48000)*.5);
    const samples=r.push(input).slice(2).flatMap(f=>Array.from(f));
    return Math.sqrt(samples.reduce((s,v)=>s+v*v,0)/samples.length);
  }
  assert.ok(rms(12000) < rms(1000)*.05);
});
test('frame inbox bounds backlog, copies input and discards stale epochs on reset', () => {
  const inbox=new FrameInbox(8), frame=new Int16Array(512);
  for(let i=0;i<20;i++){frame.fill(i); inbox.offer(frame,0);}
  assert.equal(inbox.size,8); assert.equal(inbox.take().frame[0],12);
  inbox.reset(1); inbox.offer(frame,0); assert.equal(inbox.size,0);
  inbox.offer(frame,1); assert.equal(inbox.take().epoch,1);
});
test('playback queue drains only audible samples and flush invalidates old generations', () => {
  const q=new PlaybackQueue(24000); const old=q.generation;
  q.enqueue(new Int16Array(2400).fill(123),old); const out=new Float32Array(128);
  assert.equal(q.render(out),false); assert.ok(out[0]>0);
  q.flush(); q.enqueue(new Int16Array(100).fill(5000),old);
  out.fill(1); assert.equal(q.render(out),false); assert.ok(out.every(v=>v===0));
  const current=q.generation; q.enqueue(new Int16Array(100).fill(4000),current);
  assert.equal(q.render(out),true); assert.equal(q.queuedSamples,0); assert.equal(q.render(out),false);
});
test('playback cap fails instead of silently dropping words or growing unbounded', () => {
  const q=new PlaybackQueue(24000,1);
  assert.equal(q.enqueue(new Int16Array(24001),q.generation),false);
  assert.equal(q.queuedSamples,0);
});
