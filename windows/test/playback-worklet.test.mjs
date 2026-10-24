import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {Resampler,PlaybackQueue} from '../src/core/audio.mjs';

async function playback() {
  const registered={},messages=[];
  class Processor {port={postMessage:value=>messages.push(value),onmessage:null};}
  const source=await readFile(new URL('../src/renderer/audio-worklets.mjs',import.meta.url),'utf8');
  const context=vm.createContext({AudioWorkletProcessor:Processor,sampleRate:24000,Resampler,PlaybackQueue,
    registerProcessor:(name,ctor)=>registered[name]=ctor,Int16Array,Float32Array,DataView});
  vm.runInContext(source.replace(/^import[^\n]*\n/,''),context);
  return {processor:new registered['jarvis-playback'](),messages};
}
function packet(seconds,generation=0,sequence=1,value=0) {
  return {type:'play',generation,sequence,bytes:new Uint8Array(new Int16Array(seconds*24000).fill(value).buffer)};
}
test('a burst-delivered two-minute answer plays fully without a microphone failure or truncated words',async()=>{
  const {processor,messages}=await playback();
  for(let i=0;i<120;i++)processor.port.onmessage({data:packet(1,0,i+1,i+1000)});
  assert.deepEqual(messages,[]);
  const out=new Float32Array(24000);
  for(let i=0;i<119;i++){processor.process([],[[out]]);assert.equal(out[0],(i+1000)/32768);assert.equal(out.at(-1),(i+1000)/32768);}
  assert.equal(messages.filter(m=>m.type==='drained').length,0);
  processor.process([],[[out]]);
  assert.equal(messages.filter(m=>m.type==='drained').length,1);
  assert.equal(messages.at(-1).sequence,120);
});
test('bounded playback overflow reports one playback-specific error and rejects further stale audio until flush',async()=>{
  const {processor,messages}=await playback();
  for(let i=0;i<602;i++)processor.port.onmessage({data:packet(1,0,i+1)});
  assert.equal(messages.filter(m=>m.type==='playback-error'&&m.reason==='capacity').length,1);
  processor.port.onmessage({data:{type:'flush',generation:1}});
  processor.port.onmessage({data:packet(1,0,603)});
  processor.port.onmessage({data:packet(1,1,604)});
  processor.process([],[[new Float32Array(24000)]]);
  assert.equal(messages.at(-1).type,'drained');assert.equal(messages.at(-1).generation,1);assert.equal(messages.at(-1).sequence,604);
});
